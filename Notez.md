# Study Notes

## Table of Contents
1. [Payment Service](#1-payment-service)
2. [Kafka OCR Workers](#2-kafka-ocr-workers)
3. [Kubernetes / Minikube CPU Starvation](#3-kubernetes--minikube-cpu-starvation)
4. [AWS SAA / Networking Fundamentals](#4-aws-saa--networking-fundamentals)
5. [Statistics (SPC)](#5-statistics-spc)
6. [.NET: DbContext and DbContextFactory](#6-net-dbcontext-and-dbcontextfactory)
7. [Spring Annotations and Code Walkthrough](#7-spring-annotations-and-code-walkthrough)
8. [Stage 4 Deploy: Reverse Proxy and First Login](#8-stage-4-deploy-reverse-proxy-and-first-login)
9. [DynamoDB vs Cassandra](#9-dynamodb-vs-cassandra)
10. [Memory Page](#10-memory-page)
11. [Protocol Layers](#11-protocol-layers)
12. [DNS](#12-dns)
13. [Atlas Terraform Specification](#13-atlas-terraform-specification)

---

## 1. Payment Service

### 1.1 Checkout retry guard: `hasCheckoutAttemptSince`

- `hasCheckoutAttemptSince(cutoff)` checks whether there is an attempt within the last *threshold* seconds (30s).
- `cutoff = now − threshold`. For example, if the current instant is `10:00:00` and the threshold is 30s, the cutoff is `09:59:30`.
- If the last checkout attempt is stamped **after** the cutoff, it is less than 30s old. This means the previous request hadn't finished, so we return **409**.
- If the last attempt is stamped **at or before** the cutoff, 30s have passed, so it should be retried and a new checkout attempt is made.
- So the cutoff marks the start of a 30s window before the current instant. If our initial checkout attempt is stamped inside that window, 30s have not passed. Realistically, if we retry after 30s, the attempt now falls before the cutoff, and only then do we retry.

> **Correction:** The original note had `isAfter()` reversed ("the current timestamp was before the cutoff, meaning it should be retried"). The comparison is `lastAttempt.isAfter(cutoff)`. **True** means the attempt is recent (409). **False** means it is stale (retry).

```
                cutoff (now − 30s)
                  ↓
---- OLD --------|---------------- NOW
                 |<----- 30s ----->|
                 |
          attempt inside this window?
                 |
            YES → 409
            NO  → retry
```

```mermaid
flowchart TD
    A[Checkout request arrives] --> B["cutoff = now − 30s"]
    B --> C{"lastAttempt.isAfter(cutoff)?"}
    C -->|"Yes: attempt is less than 30s old"| D["409 Conflict<br/>previous request still in flight"]
    C -->|"No: attempt is 30s+ old"| E["Retry<br/>create new checkout attempt"]
```

### 1.2 Checkout URL flow (Stripe → browser redirect)

1. `startCheckout` takes the session created from Stripe and calls `markCheckoutCreated`, which sets the `checkoutUrl` in `Payment`.
   - Note: `PaymentResult` is a record declared at the end of `PaymentService` with `(Payment payment, boolean replayed)`.
2. `createPayment` then wraps the result in a `PaymentResult`.
3. The controller calling `createPayment()` uses `.replayed()` to decide the HTTP response code.
4. The controller constructs the `PaymentResponse` record from `Payment`, using `from()` to copy it, including `getCheckoutUrl()`.
5. Spring's Jackson then turns that record into JSON, and each record field becomes a JSON key.
6. Finally, in `PayPanel`, `payment.checkoutUrl` reads the key for the redirect.

```mermaid
flowchart TD
    A[Stripe Checkout Session] --> B[startCheckout]
    B --> C["markCheckoutCreated<br/>sets Payment.checkoutUrl"]
    C --> D["createPayment<br/>returns PaymentResult(payment, replayed)"]
    D --> E{"Controller: result.replayed()?"}
    E --> F["Pick HTTP status code"]
    F --> G["PaymentResponse.from(payment)<br/>copies getCheckoutUrl()"]
    G --> H["Jackson serializes record to JSON<br/>each field becomes a key"]
    H --> I["PayPanel reads payment.checkoutUrl"]
    I --> J[Browser redirects to Stripe]
```

### 1.3 Money representation

- Amount is usually stored in the smallest unit of the currency (e.g. cents for USD, as an integer/`long`), but here it's just going to be 1.
- `BigDecimal` is used for cases where we need arithmetic calculations, because a traditional `double` is a binary floating point. Some decimal fractions can't be represented exactly in binary.

> **Correction:** `0.1` isn't stored as something like "0.1;01000101". In binary, 0.1 is an infinitely repeating fraction, so a `double` stores the nearest representable value, `0.1000000000000000055511151231257827...`. That's why `0.1 + 0.2 != 0.3` with doubles.

- We need to explicitly define the rounding policy as well (`RoundingMode`), depending on whether we want to round up or down in different cases (e.g. `HALF_UP`, `HALF_EVEN`).

> **Note:** Create `BigDecimal` from a string or `BigDecimal.valueOf(...)`, not `new BigDecimal(0.1)`. The latter copies the double's binary error exactly.

### 1.4 MongoDB transactions and the outbox

- The current MongoDB can't handle multi-document transactions. You can't chain multiple queries updating different documents in a single transaction, because a standalone Mongo doesn't offer the option to bundle several writes into one all-or-nothing unit.
- If we start Mongo in **replica-set mode** (a single-node replica set is enough for development), we get access to the transaction machinery. This lets us commit and roll back a transaction as one unit, e.g. in the case of crashes. For example, in Postgres:

```sql
BEGIN;
  UPDATE payments SET status = 'SUCCEEDED' WHERE id = 42;
  INSERT INTO outbox (...) VALUES (...);
COMMIT;   -- a crash before this line undoes both
```

- With transactions, we could separate the outbox into its own **collection**.
- It's because of this limitation that we placed the outbox inside the payment document. A write to a single document is always atomic in MongoDB, so one write gives you the all-or-nothing guarantee.
- This stops being the case when one operation triggers updates across genuinely different documents.

---

## 2. Kafka OCR Workers

### 2.1 Topology

- A single Kafka broker is shared by all pods. It is a Docker Compose container living **outside** the Kubernetes cluster, whereas the pods are inside the Minikube cluster.
- The pods communicate with the broker from within the k8s cluster through an address and port reachable from inside the cluster.
- The consumer group is defined in the worker's properties. Since all 3 pods run the same image, they are in the same group. The broker sees 3 members and splits the partitions between them.
- Each active consumer has its own thread.
- The **workers** are really just another application we isolated for processing OCR. They are the consumers in a consumer group, and **Kafka** coordinates them. The API sends the capture ID to a topic, and the key decides which of the three partitions it goes to.

### 2.2 Offsets ("bookmarks")

- The `@KafkaListener` annotation means Spring Kafka handles progress tracking in consumer groups on its own.
- Kafka stores a bookmark (the **offset**) in an internal topic called `__consumer_offsets`. It is like a pointer that moves across the partition, indicating how far a consumer group has progressed within that partition.
- Spring's listener loop behind `@KafkaListener` polls the broker, gets a batch of messages, and calls the `handleUpload()` method once per message. After the batch is processed, it tells the broker to move the bookmark past this batch (default `AckMode.BATCH`).
- When a worker dies midway through processing:
  1. The bookmark was not moved.
  2. The broker realizes that the member is gone and triggers a **rebalance**.
  3. The partition is reassigned to one of the remaining workers.
  4. That worker picks up again from where the bookmark left off.

> **Note:** This means a message can be processed **more than once** (at-least-once delivery). `handleUpload()` should be idempotent, e.g. skip if the capture is already processed.

### 2.3 How a message ends up at a worker

1. The API calls `kafkaTemplate.send("capture-uploads", captureId, captureId)`.
   - The first `captureId` is the **key**. It is hashed, so the same ID always gets sent to the same partition.
   - The second is the **value**, so the listener/consumer can use it for database lookups.
2. The producer client hashes the key and picks a partition from it (e.g. P1). The same key always lands on the same partition.
3. The broker appends the message to P1.
4. The worker consumer assigned to P1 picks it up on its next `poll()`.
5. Spring calls `handleUpload(captureId)`, and after the batch, it commits the bookmark.

```mermaid
sequenceDiagram
    participant API as API pod
    participant Prod as Producer client (KafkaTemplate)
    participant Broker as Kafka broker
    participant Worker as Worker (assigned P1)

    API->>Prod: send("capture-uploads", captureId, captureId)
    Prod->>Prod: hash(key) → partition P1
    Prod->>Broker: produce to P1
    Broker->>Broker: append message to P1
    Worker->>Broker: poll()
    Broker-->>Worker: batch of messages
    Worker->>Worker: handleUpload(captureId) per message
    Worker->>Broker: commit offset (move bookmark)
```

### 2.4 Open concern: error handling

- With the default `DefaultErrorHandler`, a failing message is retried back to back with no delay, **10 delivery attempts in total** (1 + 9 retries). After that, it is logged and skipped.
- We need:
  - **Exponential backoff** between retries (`ExponentialBackOff`).
  - A **recovery mechanism**, so failed messages go to another topic instead of disappearing. `DeadLetterPublishingRecoverer` does this and publishes to `<topic>.DLT` by default.

### 2.5 Kafka broker address advertising

- Services in their own containers have an independent networking environment. In a plain localhost setup, the APIs can communicate through the Windows loopback interface. Containers need to rely on the Docker network, and Compose provides this via the service name.
- The Spring Boot pods connect to Kafka fine initially (the **bootstrap** connection). Upon connection, Kafka returns the broker's metadata, which advertises its address as `localhost` (relative to itself). When the producer (`KafkaTemplate` in the Spring pods) tries to connect to that address, it can't find a broker listening at localhost inside its own pod.
- Because Kafka is designed to work as a distributed cluster, each partition may be handled by a different broker, hence the need for separate, reachable addresses.

```mermaid
sequenceDiagram
    participant Client as Spring pod (KafkaTemplate)
    participant Kafka as Kafka broker
    Client->>Kafka: 1. Bootstrap connect (configured address)
    Kafka-->>Client: 2. Metadata: "I am at advertised.listeners address"
    Client->>Kafka: 3. Reconnect using the ADVERTISED address
    Note over Client: If advertised = localhost:9092,<br/>the pod connects to itself and fails
```

**Kafka settings:**

- `listeners=PLAINTEXT://0.0.0.0:9092`
  - `PLAINTEXT` is a non-TLS Kafka protocol. The producer sends structured binary requests to the broker without TLS encryption (`KafkaTemplate` takes care of the protocol). If encryption is needed, `SSL` (Kafka's protocol with TLS enabled) is used.
  - `0.0.0.0` means listen on all network interfaces.
- `advertised.listeners=PLAINTEXT://kafka:9092`
  - `kafka` is the hostname for a single-broker example. In a multi-broker cluster, each broker needs to advertise an address that uniquely identifies it **and** is reachable from the client's network.

> **Note:** `kafka` only resolves inside the Docker Compose network. If pods inside Minikube connect to a broker living in Compose, the advertised address must be reachable from the pods (e.g. `host.minikube.internal:<port>`). A common pattern is two listeners, e.g. `INTERNAL://kafka:9092` for Compose and `EXTERNAL://<host-reachable-address>:9094` for the cluster.

- Different brokers can share the same port number when they are distributed on different machines, because their IPs differ. If all brokers live on the same machine, the address (hostname/IP + port) must still be unique, so for local development, using different port numbers per broker is common.

---

## 3. Kubernetes / Minikube CPU Starvation

### 3.1 Container hierarchy

- **Node:** the machine that runs the pods. With the Docker driver, this is the Minikube container itself, not something within it.
- **Kubelet:** the k8s agent that runs on every node. It:
  - Starts and stops pods assigned to its node.
  - Reports the pods' status and node capacity back to the API server.
- **API server:** the k8s central point for reading and changing cluster state. Components read information from it. It checks and validates permissions and persists relevant state to **etcd** (k8s's persistent storage).
- **Scheduler:** lives within the node. Since it can't inspect the hardware itself, it simply trusts what the kubelet reports.

```
Docker Desktop Linux VM (kernel sees 16 CPUs)
└─ minikube container  ← the Node; cgroup cap 200 ms / 100 ms (= 2 CPUs)
   ├─ kubelet, kube-scheduler, API server, etcd   ← Kubernetes itself lives in here too
   ├─ API pods ×3      (request 250m each = 750m)
   ├─ Worker pods ×3   (request 500m each = 1500m)
   └─ Prometheus, Grafana, exporters, ...
                        total requests ≈ 2700m (2.7 CPUs)
```

**What happens when you run `kubectl get nodes`:**

1. `kubectl` sends an HTTP request to the Kubernetes API server.
2. The API server handles the request and returns the node information.
3. `kubectl` displays the result in your terminal.

So `kubectl` is the client, and the API server is the server that handles the request.

### 3.2 Why the node reports 16 CPUs

- The kubelet finds the node's capacity by asking the kernel. The Minikube container shares the Docker VM's Linux kernel, which in this case has **16 CPUs**.
- The cap on the Minikube container is declared elsewhere, in a **cgroup** file. A cgroup cap is a rule on how much CPU **time** is allowed, not how many CPUs exist.
- The kubelet doesn't read that cap, by design, so it reports the VM's hardware (16 CPUs).

### 3.3 Requests ("bookings") vs limits

- A **booking** is the pod's resource **request** defined in `deployment.yaml`, e.g. `cpu: "250m"` is a quarter of a full CPU. `worker.yaml` books `500m` per worker.
- 3 APIs (750m) + 3 workers (1500m) + Prometheus, Grafana, exporters, etc. comes to around 2700m, i.e. **2.7 CPUs**.
- When the scheduler compares the bookings, it sees that the node's reported capacity (16) can fit them. This is a **requests** check, not a limits check. So it simply places all the pods.
- However, it is the Docker VM's Linux kernel that enforces the cgroup cap, so in reality there is only 2 CPUs' worth of CPU time for all the pods. This causes **starvation**.

### 3.4 CFS period and quota

The kernel cap has two variables:

- **Period:** how often the budget (quota) refills. The default Linux period is **100ms**.
- **Quota:** the total CPU execution time allowed per period, **summed across all CPUs**.
  - Two cores can each contribute 100ms of work in parallel, giving a total of 200ms while only 100ms of real time passes.
  - Quota therefore scales with the number of CPUs: busy cores consume CPU time n times as fast as one core.
  - Example: one core needs 200ms of real time to use up a 200ms quota, but two cores need only **100ms**.

**Period trade-off:**

- **Shorter periods:** more frequent replenishment and smoother CPU availability, but more quota accounting and bookkeeping.
- **Longer periods:** longer throttling pauses once the quota is exhausted.

**Formula:**

```
quota = CPU limit × period
```

- The limit defines utilization **in units of one CPU core**. A limit of 0.5 means a full core working for half the time, **not a smaller core**. It will only execute for half of the real time elapsed, e.g. a 0.5 limit over a 100ms period gives 50ms of execution.
- The period plays two roles:
  1. It is a recurring schedule, independent of the CPU execution time.
  2. It acts as a **real-time accounting window**, tracking how much real time has elapsed. It is used in the formula to answer: "if the CPU runs this fraction of the time (relative to real time), how much CPU execution time is that?"
- A longer period favours tasks that need to run in larger chunks in one go. With a 0.5 limit:
  - **1s period → 500ms quota:** an 80ms task finishes before the quota needs replenishing.
  - **100ms period → 50ms quota:** the same 80ms task is throttled after 50ms and must wait for the next refill to finish.

### 3.5 Pending vs starvation

| | Pending | Starvation |
|---|---|---|
| **What** | A new pod's **request** is greater than what remains on the node (capacity minus existing bookings) | Real CPU **demand** is bigger than the CPU time actually available |
| **Symptom** | The pod is queued and not scheduled; it retries until a pod is deleted or another node is added | Every pod is `Running`, but very slow |
| **Checked by** | Scheduler (requests vs reported capacity) | Kernel (cgroup quota) |

### 3.6 The original dilemma

- Minikube's `--cpus 2` causes starvation not by restricting the pods to 2 cores, but to **2 CPUs' worth of execution time**. The kernel can choose to run threads on any of the 16 cores.
- There are 8 JVMs, each running multiple threads at startup. In the worst case, all 16 cores run threads at once and share the 200ms quota, so each core only gets 12.5ms (200 / 16) of execution before the whole container is paused for the remaining 87.5ms of the period.
- **Regardless**, the throttling pattern itself wasn't the cause of the slowness. The demand was simply too high for a 200ms-per-100ms supply.
- This is why the fault lies in **starvation, not pending**. We initially suspected the 2.7 CPU bookings wouldn't fit 2 CPUs, but that isn't what happened: 16 CPUs were reported to the scheduler, so every pod was scheduled and running, just very slowly.

---

## 4. AWS SAA / Networking Fundamentals

### 4.1 CIDR and AWS Organizations

- **CIDR** defines the IP address range available for the VPC and its subnets.
- A company can have multiple AWS accounts for managing the different services it offers, e.g. one for food, one for payments. Each has its own root user and resources.
- The **management account** is one of the AWS accounts. It is assigned a special organizational role: it enforces **SCPs** (Service Control Policies) and handles **consolidated billing**.
- The other accounts are **member accounts** under the **AWS Organization**.

> **Note:** SCPs restrict member accounts. They do not restrict the management account itself.

### 4.2 Web server (public subnet)

A web server in the public subnet acts as a **receptionist** for incoming requests:

- **Serves static files** (HTML, CSS) without involving the application server.
- **Forwards requests** that require business logic to the application server in the private subnet, using routing rules that match the URL against where it should go.
- **Can terminate TLS**, depending on which layer HTTPS terminates at:
  - At **Nginx** (the web server): Nginx handles the TLS handshake.
  - At an AWS **Application Load Balancer**: the ALB handles it, and forwards the decrypted request to the web server.

> **Correction:** The ALB normally sits **in front of** the web server (client → ALB → Nginx → app), so when TLS terminates at the ALB, the ALB forwards to the web server, not the other way around.

### 4.3 TLS handshake (after TCP is established)

1. **Client Hello:** the browser/client presents its TLS version and cryptographic options to the server.
2. **Server Hello + certificate:** the server (the TLS endpoint) selects cryptographic settings it supports and presents its **certificate**, an identity card ensuring the server serving the website is legitimate. A certificate contains:
   - The subject (domain name the certificate is for).
   - The site's **public key**.
   - A validity period.
   - The issuer.
   - A signature (the issuer's signature over the certificate's contents).

   **Chain validation:**
   1. The server sends the website's certificate along with an **intermediate certificate (IC)**. The IC belongs to the issuer of the website's certificate, which the root CA (anchor) has delegated the right to issue certificates.
   2. The browser uses the IC's public key to verify the signature on the site's certificate. If valid, it proves the certificate's contents haven't been altered and were signed by whoever holds the IC's private key. This doesn't yet prove that the IC itself is trustworthy.
   3. The browser then uses the **root certificate's** public key (already in the browser/OS trust store) to verify the signature on the IC. If that is valid, the IC can be trusted.

   > **Note:** The server also proves it owns the certificate by signing the handshake with its **private key** (the `CertificateVerify` message). Without this, anyone could replay a public certificate.

3. **Key establishment:** the client and server establish encryption keys, after which they exchange encrypted HTTP data.
   1. Key derivation starts with **Diffie-Hellman key exchange**. Both sides agree on two public numbers: `p`, a large prime, and `g`, a base for exponentiation.
      ```
      public value = g^x mod p
      ```
      Each side generates its own private value independently, calculates its public value with `x` set to that private value, and sends it across the network.
   2. Each side computes the **shared secret** by raising the other's public value to its own private value. Both get the same result:
      ```
      A = g^a mod p          B = g^b mod p
      S = B^a mod p = (g^b mod p)^a mod p = g^(ab) mod p
      S = A^b mod p = (g^a mod p)^b mod p = g^(ab) mod p
      ```
      This works because taking the remainder first and then raising to a power gives the same remainder as raising first and then taking the remainder.
   3. TLS 1.3 then uses **symmetric cryptography**. The shared secret is fed into a key-derivation function, from which both sides independently produce the same **client** and **server traffic keys**. The client encrypts with the client key and the server decrypts with that same key, and vice versa for the server key.

> **Note:** The order above matches TLS 1.2 conceptually. In **TLS 1.3**, the client sends its DH public value already in the Client Hello, so keys are derived immediately and the certificate is sent **encrypted**. TLS 1.3 also typically uses elliptic-curve DH (ECDHE) rather than plain `g^x mod p`, but the idea is the same.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server
    C->>S: Client Hello (TLS versions, cipher options, DH public value)
    S->>C: Server Hello (chosen settings, DH public value)
    Note over C,S: Both compute shared secret g^(ab) mod p,<br/>derive traffic keys
    S->>C: Certificate + intermediate certificate (encrypted)
    S->>C: CertificateVerify (signed with server private key)
    C->>C: Verify site cert with IC public key,<br/>verify IC with root public key
    C->>S: Finished
    Note over C,S: Encrypted HTTP using symmetric traffic keys
```

### 4.4 Private subnets, Security Groups and NACLs

- We segregate the application and database servers inside the private subnet so no outsiders can contact them directly, minimizing the attack surface.
- **Security groups** and **NACLs** (Network Access Control Lists) are the layers of security beneath that:
  - **Security group:** attached to the instance's ENI; **stateful** (return traffic is allowed automatically).
  - **NACL:** applied at the subnet level; **stateless** (inbound and outbound rules are evaluated separately).

### 4.5 VPC endpoints

**Gateway endpoint** (S3 and DynamoDB only)

- Adds one entry to the route table that directs the service's traffic through the endpoint.
- The destination is a **prefix list**, e.g. the S3 prefix list represents a collection of IP address ranges. If **outgoing** traffic's destination IP matches that range, the route table sends the packets to the endpoint (an identifier the VPC knows how to handle), instead of going through a NAT gateway and IGW.

**Interface endpoint** (AWS PrivateLink)

- Creates an **ENI** (Elastic Network Interface) with a private IP address in a subnet.
- The application uses the service's private DNS name, which resolves to that private IP. The request travels through AWS's private network to the supported service, without needing a NAT or IGW.
- An **ENI** is a virtual network card attached to a resource in the VPC. For example, the application server in the private subnet has an ENI with a private IP, security groups, a MAC address (the identifier of the network interface), etc.
- The interface endpoint sits in the private subnet alongside the application server and connects to the requested service via AWS's internally managed network infrastructure.

```mermaid
flowchart LR
    subgraph VPC
        App["App server<br/>(private subnet)"]
        RT[Route table]
        ENI["Interface endpoint ENI<br/>private IP"]
    end
    App --> RT
    RT -->|"destination matches S3 prefix list"| GW[Gateway endpoint] --> S3[(S3 / DynamoDB)]
    App -->|"private DNS resolves to ENI IP"| ENI -->|PrivateLink| SVC["Other AWS service<br/>(e.g. SSM, Secrets Manager)"]
```

### 4.6 IGW vs NAT gateway

Both translate between private and public addresses, but differently:

- **Internet Gateway (IGW):** translates between a resource's own **public IPv4 address** (assigned to the resource and managed by AWS) and its private IP, both ways. The public IP is never configured on the machine; its OS only knows its private IP.
- **NAT gateway:** translates a resource's private IP into the **NAT gateway's own** public IP, for **outbound-only** internet access. The NAT gateway itself sits in a public subnet and sends traffic out through the IGW.

### 4.7 SSH public-key authentication

1. The client connects to the destination server's public IP. First, the **server** proves its identity with its host key, and both sides establish an encrypted session.
2. The client claims it owns the private key matching a public key the server has stored (in `~/.ssh/authorized_keys`).
3. The client signs data unique to this session using the private key, creating a digital signature, and sends it to the server.
4. The server uses the stored public key to verify that the signature was made by the matching private key. If it matches, the server trusts your identity and opens the shell.

> **Correction:** In SSH-2, the server doesn't send a random piece of text to sign. The client signs the **session identifier** (derived from the key exchange), which serves the same purpose: it's unique per connection, so a signature can't be replayed.

### 4.8 Spread vs Partition placement groups

- **Spread:** places each instance on **different physical racks/hardware**, so if one fails, the others don't. Limited to 7 running instances per AZ per group.
- **Partition:** groups instances into partitions, and each partition has its **own set of racks**.
  - E.g. if each of 2 partitions has 3 servers, servers in the same partition may share a rack, but a server from one partition can't share a rack with a server from another.
  - The purpose is to prevent a rack failure from affecting servers in different partitions, which is the same idea as Spread. The difference is that **Partition is designed for workloads that need many instances organized into groups** (e.g. Kafka, HDFS, Cassandra).

### 4.9 SAN vs NAS

**Storage Area Network (SAN): block storage**

- A dedicated network giving servers access to shared storage. A database server, for example, connects to the shared storage system and uses it as if it were a locally attached disk.
- This is achieved through a storage networking standard such as **iSCSI**, which relays storage commands across an IP network to a remote **storage array** (a dedicated storage system with multiple SSDs/HDDs and network interfaces). The array's controller finds the requested data and returns it.
- The database receives the response as though it read the data from a local disk drive.

**Network Attached Storage (NAS): file storage**

- Also dedicated storage, but it provides **files** instead of raw storage blocks, arranged in a hierarchical folder structure like on a regular PC.
- **Access flow:**
  1. We access the shared folder using a path on our local device (a network path such as `\\fileserver\Engineer`). Windows resolves the server name to a network address.
  2. It connects to the file server using **SMB** (Server Message Block), the protocol commonly used for network file sharing on Windows.
  3. The NAS checks permissions. Opening `\\fileserver\Engineer` in File Explorer only requests the **directory listing** for it, not the files' contents.
  4. Only when you actually open a file does Windows ask the NAS to send that file's data across the network.
- Once the file bytes arrive on your PC, they're held in memory and maybe cached locally, or you can download a local copy.
- If the network drops while the file is open, content already loaded can still be displayed, but navigating to a part that needs uncached data may fail. E.g. for a 10-page PDF, the first request may have only fetched part of the file, so later pages may not have loaded yet.

### 4.10 IMDSv2 (Instance Metadata Service v2)

- **IMDS** is an API (at `169.254.169.254`) that an EC2 instance calls to ask about its own environment.
- To obtain temporary role credentials for AWS services:
  1. The server sends an HTTP **PUT** to `/latest/api/token`, requesting a **session token** and specifying its TTL in a header (`X-aws-ec2-metadata-token-ttl-seconds`).
  2. The server attaches that token in a header (`X-aws-ec2-metadata-token`) for all subsequent **GET** metadata requests.
  3. IMDS returns the temporary role credentials on `GET /latest/meta-data/iam/security-credentials/<role-name>` + session token. STS is not contacted directly by the instance; AWS handles the issuance behind the scenes.
- **Why PUT instead of GET (SSRF defence):**
  - SSRF (Server-Side Request Forgery) vulnerabilities usually only let an attacker trigger simple **GET** requests and rarely allow custom header injection. Requiring a PUT plus a custom header blocks basic SSRF attempts.
  - IMDSv2 also **rejects token requests containing `X-Forwarded-For`**, so requests relayed through a misconfigured reverse proxy fail.
  - The token response has an IP **hop limit** of 1 by default, so it can't travel beyond the instance (e.g. to another host).

> **Correction:** The original said reverse proxies and firewalls are configured to block inbound PUT. The protection actually comes from SSRF bugs typically being unable to send PUT/custom headers, plus IMDSv2 itself refusing proxied requests and limiting hops.

```mermaid
sequenceDiagram
    participant EC2 as App on EC2
    participant IMDS as IMDS (169.254.169.254)
    EC2->>IMDS: PUT /latest/api/token<br/>X-aws-ec2-metadata-token-ttl-seconds: 21600
    IMDS-->>EC2: session token
    EC2->>IMDS: GET /latest/meta-data/iam/security-credentials/role<br/>X-aws-ec2-metadata-token: token
    IMDS-->>EC2: temporary credentials (AccessKey, Secret, SessionToken)
```

### 4.11 AWS Systems Manager (SSM), SSM Agent, Session Manager plugin

- The **SSM Agent** on the Linux server first establishes an **outbound** connection to AWS Systems Manager. The manager can then send instructions back through that connection; data flows both ways over this channel, so no dedicated inbound connection (or open port 22) is needed.
- **Port forwarding flow:**
  1. When we start a port-forwarding session via the CLI, the **Session Manager plugin** opens a listening socket locally and relays traffic to Systems Manager.
  2. Systems Manager forwards it through the agent's established channel.
  3. The SSM Agent connects to the target port on the instance's own `localhost` (e.g. `127.0.0.1:8080`).
  4. Since we already defined the Docker port mapping, traffic arriving at host `127.0.0.1:8080` is forwarded to port 8080 inside the API container.
- **The response travels back the same way:**
  1. The API generates a response.
  2. Docker sends it through the port mapping to the connection opened by the SSM Agent.
  3. The agent relays it back to Systems Manager.
  4. AWS transports it through the session established with the local plugin.
  5. The plugin writes the response back into the browser's local TCP connection.

```mermaid
flowchart LR
    B["Browser<br/>localhost:8080"] <--> P["Session Manager plugin<br/>(local socket)"]
    P <--> SM[AWS Systems Manager]
    SM <-->|"outbound channel opened by agent"| A["SSM Agent<br/>(EC2)"]
    A <--> H["Host 127.0.0.1:8080"]
    H <-->|"Docker port mapping"| C["API container :8080"]
```

---

## 5. Statistics (SPC)

### 5.1 Sample variance: why divide by n − 1 (Bessel's correction)

- If we calculate variance from a sample of n readings and divide by n, it tends to **underestimate** the spread of the whole population.
- The reason: deviations above and below the **sample mean** add up to 0, because the mean was calculated from those same n readings. The sample mean sits closer to its own data than the true population mean does, so the squared deviations come out smaller.
- Dividing by n − 1 means losing one **degree of freedom** (how many values can vary independently once a constraint is imposed). E.g. for 3 numbers with a known mean, the 3rd number is fixed by the 1st and 2nd.
- On average, the sum of squared deviations from the sample mean is short by **exactly one population variance**: it equals (n − 1)σ² instead of nσ².
- **Example:** true population variance σ² = 10, n = 5.
  - Squared deviations about the **true** mean would sum to about 50 on average.
  - Squared deviations about the **sample** mean only sum to about **40** (= 4 × 10).
  - Dividing 40 by 5 gives 8 (biased low). Dividing by n − 1 = 4 gives **10** (correct).

> **Correction:** The original said estimating from a sample "underestimates the population **mean**"; it's the population **variance** that gets underestimated. The section title also should be *sample* variance, not *population* variance.

### 5.2 X̄ (mean) and R (range) charts

- **X̄ chart:** tracks changes in the process's **centre**. E.g. when a nozzle becomes worn, it might consistently deposit slightly more material, producing a subgroup mean larger than for a normal nozzle.
- **R chart:** tracks changes in the subgroup's **spread**, i.e. how widely the units produced within each subgroup vary around their mean.

---

## 6. .NET: DbContext and DbContextFactory

- **DbContext** is a base class offered by **Entity Framework Core** (an ORM for .NET, the bridge between C# code and a relational database). It represents an active session with the database and:
  - Manages transactions.
  - Translates LINQ queries into database operations, so we don't write SQL by hand.
  - Retrieves results and turns them back into C# objects (and tracks changes to them).
- **DbContextFactory** is the context creator. Whenever a service needs to work with the database, it creates a fresh `DbContext` for that operation.
- **Why:** a shared `DbContext` is **not thread-safe**. If two separate operations (one adding results, one querying) use the same context at the same time, they both touch the context's internal change-tracking state and interfere with each other. EF Core throws an `InvalidOperationException` when it detects this.
- This doesn't affect **database-level** concurrency. The database has its own mechanisms for handling simultaneous reads and writes.

---

## 7. Spring Annotations and Code Walkthrough

### 7.1 Startup

**ORM / ODM**

- **Hibernate** is the ORM used for SQL with Spring (it implements JPA).
- **Spring Data MongoDB** maps classes to documents, so it is an **ODM** (Object-Document Mapper).

**`@Component` vs `@Bean`**

- `@Component` declares a **bean** on classes **we** write. Spring calls their constructor itself and injects the parameters.
- `@Bean` is used inside a `@Configuration` class for classes that come from libraries/SDKs, or ones that need builder calls to set up (`.build()`).

**Component scanning**

1. During startup, Spring scans the package of the main class (`@SpringBootApplication`) and every sub-package under it. A package is the namespace that organizes classes (the folder), e.g. `com.capturetotext.app.ratelimit`, where `com.capturetotext.app` is the main package.
2. It looks for annotated classes in each file, and each `@Component` is constructed **once as a singleton** (the default scope).
3. The beans listed in a class's constructor parameters are built first and handed in (**constructor injection**).

**Constructors and fields**

- A constructor has the same name as the class and no return type. It runs when an instance is created, usually followed by `this.x = x`, so the instance keeps the constructor parameters and can use them in its methods later.
- Fields with initializers (declared before the constructor) are initialized when the object (here, the bean) is constructed.

> **Correction:** Instance fields belong to **each object**, not the class; only `static` fields belong to the class. Because a Spring bean is a singleton, there's only one instance, so its fields are effectively shared by everyone using that bean.

**`WebMvcConfigurer`**

- An interface whose methods are optional hooks for customizing **Spring MVC** (the part of Spring that routes HTTP requests to controller methods).
- `addInterceptors(InterceptorRegistry registry)` is a callback that Spring MVC's setup code calls **once at startup**:
  1. When creating the request-routing object (the `HandlerMapping`), it creates a registry and hands it to each configurer.
  2. Our configurer registers the interceptor into the registry.
  3. Spring copies everything registered onto the router, so the interceptor joins the request pipeline.

**`@ConfigurationProperties`**

- A way of reading a whole block of keys from `application.properties` into a typed object.
- For `RateLimitProperties`, it takes every property matching the prefix and binds each key to the record's declared fields.
- The matching is **relaxed**: it ignores case, dashes and underscores, so `max-requests`, `maxRequests`, `max_requests` and `MAX_REQUESTS` all bind to `maxRequests`.
- In our case, two records/sections are bound, one for `payment.*` and one for `api.*`. The bean is injected into the interceptor, which picks the matching limit at runtime based on the request (e.g. its path). *(Verify this against the interceptor code.)*

> **Note:** The properties class must be registered, via `@EnableConfigurationProperties(RateLimitProperties.class)` or `@ConfigurationPropertiesScan`.

### 7.2 Login (Keycloak, OAuth 2.0, OIDC, PKCE)

**Keycloak basics**

- A Keycloak **realm** is an isolated logical container. Users in a realm can only access the applications defined within it; they can't log in to applications in another realm, nor see the users in that realm.
- The test username and password come from the `realm.json` we wrote. Keycloak reads it, hashes the passwords and stores them inside the container where it lives.
- **OAuth 2.0** handles **authorization** (what the client may access).
- **OIDC** (OpenID Connect) is the layer on top that handles **authentication**: it hands out an **ID token** proving who the user is.

**PKCE (Proof Key for Code Exchange)**

PKCE prevents a stolen authorization code from being exchanged for tokens.

1. At every login attempt, the browser creates a one-time random secret (the `code_verifier`) and hashes it with SHA-256 (the `code_challenge`). It sends the **hash** with the login request to Keycloak.
2. The user logs in. Keycloak remembers the challenge and sends back a temporary **authorization code**.
3. The browser sends the code back together with the **original, unhashed** verifier.
4. Keycloak hashes the verifier and checks it matches the challenge it received earlier.
5. If it does, the client requesting the token is proven to be the same one that started the login, and Keycloak sends the **access token**.

```mermaid
sequenceDiagram
    participant B as Browser (keycloak-js)
    participant K as Keycloak
    B->>B: generate code_verifier,<br/>code_challenge = SHA256(verifier)
    B->>K: login request + code_challenge
    K->>K: user logs in, store challenge
    K-->>B: redirect back with authorization code
    B->>K: code + code_verifier
    K->>K: SHA256(verifier) == stored challenge?
    K-->>B: access token (+ ID token, refresh token)
```

These steps are packaged inside `keycloak-js`:

- `login()` builds the challenge and redirects to Keycloak.
- When the browser returns, `init()` detects the code in the URL, completes the exchange and stores the tokens.

**Access token (JWT)**

The access token is a **JWT**: a JSON object signed by Keycloak, which the browser attaches to every subsequent server request. Roughly:

```json
{
  "sub": "permanent UUID issued for the user",
  "iss": "the issuer, who signed it (Keycloak realm URL)",
  "aud": "the audience, who this token is meant for (the API)",
  "exp": "the expiry, by default 5 minutes after issue"
}
```

> **Correction:** `keycloak-js` keeps tokens **in memory** by default, not in `localStorage`.

**Server-side validation (`spring-boot-starter-oauth2-resource-server`)**

1. Every API request includes the token in the `Authorization: Bearer <token>` header.
2. Spring downloads Keycloak's list of public keys (the **JWKS** endpoint) and caches them.
3. For every request, Spring checks the token locally:
   1. It picks the matching public key using the token's `kid` header.
   2. It hashes the token's header and payload.
   3. It uses the public key to verify that the signature matches that hash.
   4. It checks `exp`, `iss` (and `aud`, if configured).
4. If valid, Spring stores the authenticated JWT in the **SecurityContext** for this request.
5. `@AuthenticationPrincipal Jwt jwt` in a controller injects that already-verified token, so the controller can read claims such as `jwt.getSubject()` (the user's `sub`) to know **which user** made the request.

> **Correction:** With RSA signatures, verification isn't "decrypting" the signature. It's a verify operation using the public key that confirms the signature was produced over that exact hash by the matching private key.

## 8. Stage 4 Deploy: Reverse Proxy and First Login

### 8.1 Why Stage 4

- Stage 1 has **no inbound SG rules**. `localhost:8080`/`8180` only work because the Session Manager tunnel maps **my PC's** ports to the server's.
- Once public, `localhost` in a visitor's browser means **their own laptop**, so every browser-facing URL must become the domain.
- It also needs **HTTPS**: Stripe only sends webhooks to HTTPS, Keycloak's `sslRequired: external` refuses plain HTTP off localhost, and the PKCE Web Crypto API (used by `keycloak-js`) only works on HTTPS or localhost.

### 8.2 Who opens what

A request to `https://example.com` passes three separate pieces:

1. **SG** (AWS firewall) opens 80/443. Without it, packets are dropped before reaching the server.
2. **Docker** publishes the Caddy container's `80:80`/`443:443`, so traffic on the server's ports goes to Caddy.
3. **Caddy** (reverse proxy) decrypts the HTTPS, reads the **Host header**, and makes a **new** plain-HTTP request to the right container.

> **Correction:** Caddy doesn't open ports and isn't a tunnel. A tunnel forwards raw bytes blindly; a proxy reads each request and decides where it goes, which is how one port 443 serves two sites.

```
Internet → IGW → EC2 (SG: 80, 443 only)
                  └─ caddy :80/:443   ← only published ports
                       ├─ example.com      → api:8080      (page + /api/**, incl. webhook)
                       └─ auth.example.com → keycloak:8080
                     worker, kafka, mongo, redis: no published port
```

```
example.com {
    reverse_proxy api:8080
}
auth.example.com {
    reverse_proxy keycloak:8080
}
```

- The Stripe webhook needs no special route. It's just a path on the API (`permitAll`, protected by Stripe's signature).
- **SAA comparison:** normally IGW → **ALB** (public subnet, ACM cert) → app servers (private subnet). The ALB *is* a managed reverse proxy. We skip the ALB (~$16/mo) and private subnet (needs NAT, ~$33/mo), so Caddy takes the ALB's place on the server itself. Stage 5 moves to the ALB shape.

### 8.3 First visit, step by step

1. **Page load:** `https://example.com` → DNS gives the Elastic IP → Caddy (`Host: example.com`) → `api:8080` serves the React files.
2. **`initAuth()`** runs before anything renders. `keycloak-js` builds the login URL from `VITE_KEYCLOAK_URL` (baked in at build time) and sets `window.location` → **redirect 1** (done by JS, not Caddy).
3. **Login page:** `auth.example.com` → same IP, same Caddy, but `Host: auth.example.com` → `keycloak:8080`.
4. **Alice logs in.** Keycloak replies 302 to `https://example.com/?code=...` → **redirect 2** (only if listed in the realm's `redirectUris`).
5. **Code → tokens:** `keycloak-js` fetches `auth.example.com/.../token` with the code + PKCE verifier. Cross-domain, so `https://example.com` must be in `webOrigins` (CORS).
6. **API calls** carry `Authorization: Bearer ...` → Caddy → API.
7. **API validates** the token, fetching keys **internally** from `http://keycloak:8080` (never via Caddy), and checks `iss` = `https://auth.example.com/realms/capture-to-text`.

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as Caddy
    participant A as api:8080
    participant K as keycloak:8080
    B->>C: GET example.com
    C->>A: forward
    A-->>B: React files
    B->>C: redirect 1 → auth.example.com (keycloak-js)
    C->>K: forward
    K-->>B: login page → 302 example.com/?code (redirect 2)
    B->>C: POST auth.example.com/token (code + verifier)
    C->>K: forward
    K-->>B: access token
    B->>C: /api/... + Bearer token
    C->>A: forward
    A->>K: JWKS keys (internal, no Caddy)
```

### 8.4 Settings that must agree

All change from `localhost` together; one leftover breaks login at the step that uses it:

- `VITE_KEYCLOAK_URL` → `https://auth.example.com` (frontend build)
- `KC_HOSTNAME` → `https://auth.example.com`. Keycloak writes this into its own pages and the token's `iss`; left as localhost, the login form would post to the visitor's laptop.
- Realm `redirectUris` / `webOrigins` → `https://example.com`
- API `issuer-uri` → the new Keycloak URL (the internal JWK URL stays `http://keycloak:8080`)
- Stripe success/cancel URLs

> **Note:** Caddy talks plain HTTP to Keycloak, so Keycloak must trust Caddy's `X-Forwarded-*` headers (`KC_PROXY_HEADERS=xforwarded`) to know the original request was HTTPS.

> **Note:** Needs an **Elastic IP** (DNS points at a fixed IP; the public IP changes on every stop/start), and port **80 stays open** for Let's Encrypt's domain check.

---

## 9. DynamoDB vs Cassandra

### 9.1 Keys

- **DynamoDB** – Partition key + sort key + secondary indexes
- **Cassandra** – Partition key + clustering columns + query-specific tables

Both use partitioning and ordering keys. Dynamo uses a **Partition key** (the key to identify an item/entry with); this key is used similar to Kafka partitioning, in how the same hash lands in the same partition. A **Sort key** is used to differentiate items within the same partition from one another, and also determines their order within it (which is what enables range queries like `begins_with` / `between`), e.g.:

| PK           | SK        | Status  | Total |
|--------------|-----------|---------|-------|
| CUSTOMER#001 | ORDER#001 | PAID    | 150   |
| CUSTOMER#001 | ORDER#002 | PENDING | 200   |

This eases query retrieval that requires exact key matching, e.g. you can query the PK to get all orders and attributes associated with them, OR with PK & SK, only that particular order.

**GSI (Global Secondary Index)** is a secondary key you can use for querying, e.g. if we want to query by Status instead, we can make status the PK and order the SK, which is just another indexed access path for the same items.

> **Note:** GSIs only support eventually consistent reads, and have their own throughput/capacity.

### 9.2 Cassandra: clustering columns

Cassandra uses a PK with **clustering columns**, and its CQL looks similar to Postgres on the surface, in how it also has the idea of tables, partitions within them via the PK, and sorting via the clustering columns (but no joins, and no filtering on arbitrary columns without an index), e.g.:

**Cassandra table: `orders_by_customer`** — Partition key / customer_id: `CUSTOMER#001`

| Order date | Order ID | Status  | Total |
|------------|----------|---------|-------|
| Oct 01     | 001      | PAID    | 150   |
| Oct 05     | 002      | PENDING | 200   |

### 9.3 Querying by another attribute

Both DB options can retrieve records based on a range within a partition; what differs between them is their overall data model and query capabilities. E.g. when we want to retrieve orders with a particular status, Dynamo can use a GSI, whilst the idiomatic Cassandra approach is to create a new table with duplicated data catered for that query specifically (secondary indexes/materialized views exist, but have limitations), e.g.:

**`orders_by_status`**
- PK: `status`
- Clustering columns: `order_date`, `order_id`

> ⚠️ Partitioning by `status` is a hot-partition risk in **both** DBs (low cardinality, e.g. everything `PENDING` lands in one partition). Usually mitigated by sharding the key (`PENDING#3`) or adding a time bucket.

> **Note that** although Cassandra's storage structure is called *"wide-column"*, realistically it is still row-oriented, unlike columnar storage like Redshift.

### 9.4 Choosing between them

One may prefer one over the other due to the dimensions each option provides and their requirements from facets like infra, data modelling, querying, consistency (deciding how many replicas must acknowledge a write/read before the operation is considered successful, vs eventually consistent reads), portability etc., e.g.:

- Cassandra's peer-to-peer distributed cluster vs Dynamo's AWS-managed distributed service
- Cassandra's finer control over nodes, replication and consistency vs AWS-managed infrastructure and replication
- Query pattern: CQL, PK/CK, query-specific tables vs PK/SK and GSI

Dynamo is more preferred for an **e-commerce application** that is built on the AWS ecosystem, wants minimal database infra management that is scalable, and suits the key-based lookup pattern. Cassandra is more preferred for a **global IoT platform** because of finer control over cluster topology and the facets listed above.

---

## 10. Memory Page

### 10.1 Pages and virtual addresses

A fixed-size, contiguous block of virtual memory (typically 4 KB on x64 Windows) that the OS manages as a unit. If memory is a huge collection of numbered byte slots, then a page is a managed chunk of those slots. The physical counterpart in RAM is called a **page frame**.

A pointer in the code points to some **virtual address**, which isn't necessarily the actual physical address in RAM, and since each process has its own virtual address space, Windows maintains per-process **page tables** which the CPU's MMU uses to translate VA into PA.

### 10.2 Paging file and views

- **Paging file** – a hidden system file on storage that Windows uses as an extension to RAM. It is the backing store for private committed memory (Memory belonging to one process only, e.g. its thread stack, heaps etc. Committed referes to how Windows reserves memory: claims a range of VA -> guarantees the backing store.) and pagefile-backed mappings (a mapping of an actual file is backed by that file instead).
- **View** – exposes/maps a virtual address range in our process, through which our code can access the mapping. But Windows doesn't necessarily have RAM allocated for every page in that range, yet.

> **Note:** PTE (Page Table Entry) is one entry in the page table, consisting of a bit for signifying whether the page is currently resident in RAM or not, the frame number it maps to, protection/permission bits, and accessed bits for indicating whether the page has been written to or touched recently.

### 10.3 Page faults

The process of physical pages being allocated in RAM is as follows:

1. CPU attempts to access some virtual address
2. MMU discovers the required page isn't mapped to a resident physical page yet (PTE not valid) 
3. CPU raises a **page fault** exception, handled by the Windows memory manager
4. Windows takes a frame from its free/zeroed page lists (trimming working sets if none are free), fills it if needed (from the paging file/backing file, or zero-filled for a brand-new page), and updates the PTE to map the virtual address to that frame
5. CPU re-executes the faulting instruction

> **Note:** multiple virtual address ranges can translate onto the same physical page; the page table essentially maps those addresses onto the same frame.

---

## 11. Protocol Layers

### 11.1 Request path

1. Client's HTTPS request (the actual request is still HTTP)
2. Browser establishes TCP with server
3. TLS encryption over that TCP connection
4. Browser sends the HTTP request within the established encrypted TLS channel (the raw request gets encrypted with TLS)
5. NLB/ALB/backend terminates the TLS, decrypting the record using the key derived during the handshake
6. *(optionally encrypt for HTTPS again)*
7. Backend

> **Note that** L7, L4, and the backend can all terminate TLS, so the choice mainly lies in configuration needs.

### 11.2 OSI model

The way you read the **OSI (Open Systems Interconnection)** model changes depending on whether you're the sender/receiver. Traditionally the model is as follows:

| Layer | Name         | Example / Note |
|-------|--------------|----------------|
| L7    | Application  | HTTP. ALB is classified as L7 due to its ability to interpret application-layer HTTP information and make routing decisions |
| L6    | Presentation | Encryption, *"conceptually"* (TLS doesn't map cleanly, it sits somewhere between L4 and L7) |
| L5    | Session      | |
| L4    | Transport    | TCP, UDP. NLB is L4 as it makes forwarding decisions using transport-layer info like IP, port, protocol etc. |
| L3    | Network      | IP |
| L2    | Data Link    | Ethernet / Wi-Fi |
| L1    | Physical     | Wires |

> **Note that** it is the underlying networking stack that's mainly responsible for the TCP/IP/TLS operations, distinct from NLB responsibilities (though NLB is capable of TLS termination depending on configuration).

### 11.3 OSI vs real architecture

This model only describes how network protocols operate and **does not translate** directly into how the actual architecture looks. E.g. even though OSI posits that a sender creating an HTTP request would be at L7, this sequential ladder doesn't really hold when real architecture is involved.

E.g. NLB is known to operate at L4, forwarding TCP/UDP/TLS traffic without inspecting it (HTTP included). Separately, it can optionally be put in front of an ALB (e.g. for static IPs or PrivateLink). Under the **NLB → ALB** setting with a TCP listener, the NLB doesn't terminate TCP, it forwards at the connection level, so the browser's TCP connection effectively ends at the ALB, and the ALB is also the TLS endpoint with which the browser does the handshake. ALB then terminates the TLS, so it can inspect the raw HTTP request and route it based on the listener rules to the backend.

On the receiving backend, data travels from L1 back to L7, in a manner that is completely independent of whether the network architecture uses an NLB, ALB, or neither. This process is called **decapsulation** (encapsulation is L7 → L1), where each layer strips its own header and reconstructs what the sender sent. E.g. the backend's L4 transport layer (NOT NLB) processes the TCP header from the ALB's connection (as ALB acts as a proxy that establishes a separate connection to the backend). Then if HTTPS is used, the backend's TLS implementation needs to decrypt TLS, and only then can the application code process the HTTP request.

**In summary:** OSI is merely a description of what each layer consists of, not a definite ladder that a network architecture needs to follow.

---

## 12. DNS

### 12.1 Zones and resolution

A **DNS zone** is an administrative container which holds the DNS records for a particular domain, where the domain/subdomain names and their records are grouped together (Route 53 calls this a *hosted zone*).

Once you purchase a domain like `kedaipay.my`, you can configure subdomains for it in your DNS provider. E.g. in Route 53, a record name and type is attached to the domain, so when a user tries `pay.kedaipay.my`, the resolver goes root → `.my` **TLD** servers → NS delegation to Route 53's name servers. Route 53 then returns the `A` (IPv4) record (an **Alias** record, since an ALB has no fixed IP), and the browser connects to the ALB with that address.

**Zone apex** is the top-level name of a DNS zone, which is actually just the domain name itself (e.g. `kedaipay.my`).

### 12.2 SOA record

**SOA (Start of Authority)** is a record that contains the administrative metadata and sync info about a zone. A simplified one may look like:

- **Primary name server** – the zone's primary authoritative name server (in Route 53, one of its assigned `awsdns` servers). Resolvers find the authoritative servers via the zone's NS records.
- **Responsible party** – the party responsible for the zone
- **Serial number** – version number for the zone; any change in the zone increments it to indicate the zone has changed. A secondary DNS server can use this number to determine whether it needs to synchronize its copy. *(Route 53 doesn't use zone transfers, so I believe it doesn't auto-increment this)*
- **Refresh** – how long a secondary DNS server waits before checking whether the primary has changed
- **Retry** – how long a secondary waits before retrying after a failed refresh
- **Expire** – how long a secondary keeps serving the zone without reaching the primary before it stops answering for it
- **Minimum** – e.g. when someone queries a non-existent DNS record/subdomain, the resolver can cache this negative result for this duration, to prevent repeated queries for the same non-existent name

---

## 13. Atlas Terraform Specification

### 13.1 Cluster layout

The **replication specs** are defined per shard (sharding = the database is split into smaller portions); one spec entry describes how one shard should be deployed.

**Electable specs** define the electable nodes (nodes that can vote and become primary, vs read-only/analytics nodes) within that region for a given shard. M0 is a shared-tier, fixed 3-node replica set (no `node_count` to set); on dedicated tiers, `node_count` = 3, 5 or 7.

**Priority** refers to how much *"this"* region is preferred for hosting the primary node (1–7, 7 being highest). With a single region it must be 7, as there is no other region to compare to.

### 13.2 Database user and password

**`auth_database_name`** is the DB Mongo uses for storing and verifying user logins. Upon apply, Atlas creates the user defined in the resource block and stores it in the cluster's `admin` DB. The cluster then uses that credential to verify every subsequent connection.

In the first `terraform apply`, after Terraform generates the password, the SSM parameter creates/stores the connection string containing username + password, which the user data script later uses at boot to inject into the env file the container uses.

> ⚠️ `random_password` is not ephemeral, it's stored in plaintext in Terraform state, so the state must be protected.

### 13.3 Connection string, SRV and the driver

**SRV** is a DNS record (Service Record) which helps MongoDB's driver look up the database servers it can connect to. E.g. if Atlas changes the underlying server infra/addresses, SRV allows the driver to discover the relevant servers through a DNS lookup itself, as Atlas updates the SRV records along with said changes, without anyone manually maintaining the list of addresses. I.e. the `mongodb+srv` in the connection string (the address and instructions an application uses to connect to a DB cluster) is the scheme defining that. The driver also reads a TXT record for options like `authSource`/`replicaSet`.

**`${...}`** in Terraform means *process this expression and paste the result here*. In this case, `replace()` swaps the `mongodb+srv://` part of the connection string Atlas produced with one that has the username + password + DB name + write properties:

- **`retryWrites=true`** – retry writes once if the primary changes midway
- **`w=majority`** – only considered a successful write once the majority of nodes have it (e.g. 2/3)

> **Note:** The password must be URL-encoded if it contains special characters.

The driver enables TLS by default for `+srv` connections because the `mongodb+srv` scheme specifies it, not because of the `+` sign itself.

The **driver** is the software library that knows how to communicate with Mongo, whilst the **client** (`MongoClient`, part of the driver's API) is the object the application uses to communicate with it. Hence, the driver mentioned above is the MongoDB Java Driver, which the pom's Maven dependency brings in. Spring Boot (not the driver) reads the MongoDB URI from `application.properties` and builds the client with it.

### 13.4 SSM and the `aws/ssm` KMS key

Every AWS account gets a key named **`aws/ssm`**, which AWS manages for SSM, and anyone in this account is allowed to use it, but only through the SSM service (no additional decrypt policy is needed, as KMS decryption is already part of the key policy). The connection string is encrypted by KMS. When we retrieve the value decrypted (`--with-decryption`), SSM checks the IAM policy for the `ssm:GetParameter` action and whether the resource in question is allowed under this action. If yes, it tells KMS to decrypt and returns the connection string as a plain string.

### 13.5 Boot script: `templatefile()` and Bash

**`user_data`** in `server.tf` passes a map of names to `templatefile()` which the template script can use; each `${}` is substituted with the values defined on the right. *(Bash's own `${VAR}` must be escaped as `$${VAR}` in the template.)*

**`$(...)`** in Bash means *run the command within and use whatever it prints*. E.g. `ssm get-parameter` does the SSM request, signed with the instance role's temporary credentials, and SSM replies with the decrypted string in JSON. `--query Parameter.Value` then picks out just the `Value` field, and `--output text` outputs it as text without quotes. `echo` prints it, and `>` redirects the output into the file `/opt/mongo.env`, which is declared in `compose.server`, which Compose uses to inject into the `api` and `worker` containers.

### 13.6 Bash flags

- **`-e`** – Exits on error, stops the script when a command fails
- **`-u`** – Unset var, reports an unbound variable rather than substituting an empty string
- **`-x`** – Prints commands as the script executes them (we turn this off with `set +x` so that the password is not logged)
- **`-o pipefail`** – Detects pipeline failure (`|` works by passing the output of one command as input to another). E.g. when we chain two operations and the latter succeeds, without this flag Bash uses the exit status of the LAST command in the pipeline, i.e. we need this flag to return a non-zero status if any command in the pipeline fails.

### 13.7 umask

Controls which permissions are removed from newly created files/directories. E.g. `umask 077`: each digit is operated on as binary, and the default base permission for files is usually `rw-rw-rw-` (owner, group, others), where r = 4, w = 2, x = 1. umask does `666 & ~077`:

```
  110 110 110
& 111 000 000   (~077)
= 110 000 000   = 600
```

I.e. only the file owner retains r & w, whilst group and others are removed. (Directories start from 777, so they become 700.) The umask must be set *before* the file is created.