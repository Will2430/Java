# Capture to Text

Upload or drag-drop an image; OCR (Tesseract, via Tess4J) runs asynchronously
in a separate worker process, decoupled from the upload request by Kafka.
Every capture is saved to MongoDB so you can browse history, and image bytes
live in MinIO (S3-compatible) so the worker can reach them from its own
process. Both the API and the worker are containerized and deployable to a
local Kubernetes cluster (minikube), with Prometheus + Grafana for
monitoring and a k6 load generator for putting the cluster under traffic
(see [step 4](#4-optional-deploy-to-kubernetes-monitor-and-load-test) and
[Known limitations](#known-limitations)).

It also pays the bill in the picture: the worker spots the amount due in the
OCR text, the user confirms it, and pays through **Stripe Checkout (test
mode)**. Payments are idempotent (an `Idempotency-Key` header backed by a
unique index) and driven by a state machine. They're confirmed only by signed
Stripe webhooks, and every state change reaches Kafka through a transactional
outbox (see [Payments](#payments-stripe-test-mode)).

**Architecture:**
```
Browser (static HTML/JS)
  → Spring Boot API → MongoDB (capture metadata) + MinIO (image bytes)
      → Kafka topic "capture-uploads"
          → Worker → Tess4J (OCR) → MongoDB (result + suggested amount)

Browser "Pay this bill" → API → Stripe Checkout (hosted card page)
Stripe ──signed webhook──► API → Payment status + outbox (one Mongo write)
                                   → OutboxRelay → Kafka topic "payment-events"
```
Each module runs either as a plain `mvnw spring-boot:run` process or as a
Kubernetes Deployment (`k8s/`) — same code, different host for reaching
Mongo/Kafka/MinIO (`localhost` locally, `host.minikube.internal` from inside
a Pod).

The API returns `202 Accepted` immediately; the client polls
`GET /api/captures/{id}` until `status` leaves `PENDING`.

Users log in through **Keycloak** (OpenID Connect). The API only accepts
requests that carry a valid Keycloak access token, and every capture and
payment belongs to the user who created it. Each user also has a **Redis
token-bucket rate limit** (see [Auth and rate limiting](#auth-and-rate-limiting)).

---

## Prerequisites

| Tool | Version used in this project | Required? |
|---|---|---|
| JDK | 17+ (built/tested with Temurin 21) | Yes |
| MongoDB Community Server | tested with 8.3.4, default port 27017 | Yes, running locally |
| Docker (with Compose) | any recent version | Yes — runs Kafka + MinIO locally, and builds the API's image |
| Maven | **not required** — the bundled Maven Wrapper (`mvnw` / `mvnw.cmd`) downloads Maven 3.9.9 automatically on first run | No |
| Tesseract OCR | **not required as a separate install on Windows/Linux/macOS** — see note below | No |
| Stripe account (test mode) + Stripe CLI | any; CLI tested with 1.53.0 | Only for payments — see [Payments](#payments-stripe-test-mode) |
| minikube + `kubectl` | tested with minikube v1.39.0, Kubernetes v1.37.0 | Only if deploying to Kubernetes — see [step 4](#4-optional-deploy-to-kubernetes-monitor-and-load-test) |

### About Tesseract / Tess4J

This project uses [Tess4J](https://github.com/nguyenq/tess4j), a JNA wrapper
around the native Tesseract + Leptonica libraries. Tess4J's Maven artifact
**bundles the native binaries for Windows** and the English (`eng`) language
data for every OS, and `worker/`'s `OcrService` extracts the language data to
a temp directory at startup (`LoadLibs.extractTessResources("tessdata")`). On
Windows you do **not** need to install Tesseract separately or download
`eng.traineddata` yourself. **Linux natives are not bundled**, so the worker's
Docker image installs `libtesseract5` from apt (see `worker/Dockerfile`) —
skip that and the container fails at the first job with `UnsatisfiedLinkError:
Unable to load library 'tesseract'`. Only the worker needs this — the API
module doesn't run OCR anymore.

The one native dependency Windows needs is the **Microsoft Visual C++ 2019
Redistributable (x64)** — Tesseract 5.x's Windows binaries are built against
it. Almost every Windows 10/11 machine already has this installed (it ships
with many other applications); if OCR calls fail with a native-library
loading error, install it from Microsoft's site and retry.

If you want to recognize other languages later, drop the corresponding
`<lang>.traineddata` file from
[tesseract-ocr/tessdata](https://github.com/tesseract-ocr/tessdata) into the
extracted `tessdata` folder (printed in the app logs, typically
`%TEMP%\tess4j\tessdata` on Windows) and call `setLanguage("eng+<lang>")` in
`OcrService`.

---

## 1. Install & start MongoDB

**Windows (winget):**
```powershell
winget install --id MongoDB.Server -e
```
This installs MongoDB as a Windows service ("MongoDB") that starts
automatically. Verify it's running:
```powershell
Get-Service MongoDB
```
If it's stopped: `Start-Service MongoDB`.

**macOS (Homebrew):**
```bash
brew tap mongodb/brew
brew install mongodb-community
brew services start mongodb-community
```

**Linux:** follow the [official MongoDB install guide](https://www.mongodb.com/docs/manual/administration/install-on-linux/)
for your distribution, then `sudo systemctl start mongod`.

The app expects Mongo at `mongodb://localhost:27017/capturetotext`
(see `src/main/resources/application.properties`). The `capturetotext`
database and `captures` collection are created automatically on the first
successful capture — no manual setup needed.

---

## 2. Start Kafka, MinIO, Keycloak and Redis

From the project root (`capture-to-text/`):
```bash
docker compose up -d
```
This starts a single-broker Kafka (KRaft mode, no separate Zookeeper),
MinIO (S3-compatible object storage) on `localhost:9000` (console on
`localhost:9001`, login `minioadmin` / `minioadmin`), **Keycloak** on
`localhost:8180` and **Redis** on `localhost:6379`. Keycloak imports the
`capture-to-text` realm from `config/keycloak/` on first start. It includes
the test users `alice` / `alice` and `bob` / `bob` (log in as each in two
browsers to see that their data is separate), and the login page also lets
you register new users. Admin console: `http://localhost:8180/admin`
(`admin` / `admin`). The API creates its
`captures` bucket automatically on startup — no manual MinIO setup needed.
`capture-uploads`, the Kafka topic, is created automatically on first
publish with 3 partitions (see `docker-compose.yml`).

Kafka exposes **two listeners**: `localhost:9092` for processes running
directly on the host (the API/worker via `mvnw`), and `localhost:29092`
(advertised inside the cluster as `host.minikube.internal:29092`) for a
Pod running the containerized API — see [step 4](#4-optional-deploy-to-kubernetes-monitor-and-load-test).
Local `mvnw` runs only ever need `9092`.

---

## 3. Run the API and the worker

Two separate processes — start each in its own terminal.

**API** (from `capture-to-text/`):
```powershell
$env:JAVA_HOME = "C:\Path\To\Your\JDK"   # only if JAVA_HOME isn't already set
.\mvnw.cmd spring-boot:run
```
```bash
# macOS/Linux
./mvnw spring-boot:run
```

**Worker** (from `capture-to-text/worker/`):
```powershell
.\mvnw.cmd spring-boot:run
```
```bash
# macOS/Linux
./mvnw spring-boot:run
```

First run of each downloads Maven itself (via its own wrapper) plus
dependencies — give it a minute. Once the API logs
`Started CaptureToTextApplication in X.XXX seconds`, open
**http://localhost:8080** in a browser. The worker has no HTTP endpoint
(it's a plain Spring context with a `@KafkaListener`) — its equivalent
"ready" line is `Started WorkerApplication in X.XXX seconds`.

To just compile/package without running:
```powershell
.\mvnw.cmd clean package
java -jar target\capture-to-text-0.1.0.jar
```
(same pattern in `worker/`, producing `capture-to-text-worker-0.1.0.jar`).

### Tests

```powershell
.\mvnw.cmd test            # API: unit tests + Testcontainers integration tests (Docker must be running)
cd worker; .\mvnw.cmd test  # worker: bill-amount extractor unit tests
```

The integration tests (`PaymentFlowIntegrationTest`) start throwaway Mongo and
Kafka containers, and replace only Stripe and MinIO with fakes. They cover 10
concurrent requests with one idempotency key, a duplicate webhook, a forged
signature, and an out-of-order webhook.

---

## Payments (Stripe test mode)

1. Create a Stripe account and stay in **test mode**. Copy the test secret key
   (`sk_test_...`).
2. Put your keys in `config/secrets.properties` (gitignored, never commit it):
   ```properties
   stripe.secret-key=sk_test_...
   stripe.webhook-secret=whsec_...
   ```
3. Install the Stripe CLI and run `stripe login` once. Get the webhook secret
   with `stripe listen --print-secret` and paste it in as above.
4. While the API runs, forward Stripe's webhooks to it (keep this terminal open):
   ```powershell
   stripe listen --events checkout.session.completed,checkout.session.expired,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed --forward-to localhost:8080/api/webhooks/stripe
   ```
5. Upload a bill image, check the detected amount, click **Pay with Stripe**,
   and pay with card `4242 4242 4242 4242` (any future expiry, any CVC).

Card details are typed on Stripe's hosted page, never on ours, so this server
only ever handles ids and statuses. A payment becomes `SUCCEEDED` only when a
signed webhook says so; the success redirect alone proves nothing.

---

## 4. (Optional) Deploy to Kubernetes, monitor, and load-test

Both modules can run as Kubernetes Deployments instead of plain processes
(don't run the `mvnw` worker at the same time — it would just be one more
consumer in the group). Mongo, Kafka and MinIO stay on the host from
steps 1-2.

```powershell
# Start the cluster. With the docker driver, minikube's container is capped at
# 2 CPU / 4 GB by default, which starves this stack. (The node still *reports*
# all of Docker Desktop's CPUs, so nothing goes Pending; Pods just run slowly.)
minikube start --driver=docker
docker update --memory 5g --memory-swap 5g --cpus 6 minikube
minikube addons enable metrics-server        # for `kubectl top`

# Build both images and load them into minikube's own container runtime
# (separate from Docker Desktop's image store — a build alone isn't enough)
docker build -t capture-to-text-api:local .
docker build -t capture-to-text-worker:local worker/
minikube image load capture-to-text-api:local
minikube image load capture-to-text-worker:local

# Stripe keys go in a Secret created from the command line, never a committed file
kubectl create secret generic capture-api-stripe --from-literal=STRIPE_SECRET_KEY=sk_test_... --from-literal=STRIPE_WEBHOOK_SECRET=whsec_...

# Apply the manifests (API + its autoscaler, worker x3, Service, ConfigMaps, Secret)
kubectl apply -f k8s/

# Verify
kubectl get pods                         # expect 1+ capture-api (the HPA decides) + 3x capture-worker, all 1/1
kubectl get hpa                          # current CPU vs the 50% target, and the replica count
kubectl port-forward svc/capture-api 8080:8080
# then open http://localhost:8080
```

`kubectl port-forward` is the reliable way to reach it locally — on
Windows with the docker driver, `minikube service --url` has to hold a
terminal open as a tunnel for the life of the connection, which
`port-forward` doesn't need. See `k8s/configmap.yaml` for how the Pod
reaches Mongo/Kafka/MinIO on the host (`host.minikube.internal`, not
`localhost` — inside a Pod, `localhost` means the Pod itself).

### Monitoring: Prometheus + Grafana

```powershell
kubectl apply -k k8s/monitoring                          # namespace: monitoring
kubectl -n monitoring port-forward svc/grafana 3000:3000
# open http://localhost:3000  ->  Dashboards -> capture-to-text  (no login)
```

Prometheus discovers scrape targets from pod annotations, so a new replica is
picked up the moment it exists. The dashboard shows requests/sec per API Pod
(the load-balancing spread), p95 latency per route, Kafka backlog per
partition, OCR jobs/sec per worker and average OCR time, CPU and heap per Pod.
Quick checks without Grafana: `kubectl top pods`, or
`kubectl -n monitoring port-forward svc/prometheus 9090:9090` for raw queries.

### Load testing: k6 inside the cluster

```powershell
kubectl delete job k6-loadtest --ignore-not-found        # Jobs are immutable: re-create to re-run
kubectl apply -k k8s/loadtest                            # 20 reads/s + 4 uploads/s for 90 s
kubectl logs -f job/k6-loadtest

# things to try while it runs, with the Grafana dashboard open:
kubectl get hpa -w                                       # watch the API autoscale from 1 Pod toward 5
kubectl delete pod <one-capture-api-pod-name>            # kill ONE Pod mid-test; the ReplicaSet replaces it, k6 sees ~no errors
```

The API's `HorizontalPodAutoscaler` (`k8s/hpa.yaml`) targets 50% of each Pod's
CPU *request*. The worker has no HPA on purpose: its load signal is Kafka lag,
not CPU, and 3 partitions cap it at 3 useful Pods.

The load generator runs *inside* the cluster on purpose: `kubectl
port-forward` pins to one Pod, so only in-cluster traffic exercises kube-proxy.
Every API response carries an `X-Served-By: <pod name>` header to see which
replica answered. Uploads are named `loadtest.png`; remove the test rows with
`db.captures.deleteMany({ sourceFilename: "loadtest.png" })`.

---

## 5. Using the client

- Drag an image onto the drop zone, click it to open a file picker, or
  paste (Ctrl/Cmd+V) a screenshot directly from your clipboard.
- The upload returns immediately; the client shows "Processing..." while it
  polls in the background, then the extracted text appears in the textarea
  with the OCR confidence score once the worker finishes. Click
  **Copy to clipboard** to grab it.
- Past captures appear in the **History** list (newest first); a capture the
  worker hasn't finished yet shows "(processing...)" with its status next to
  the timestamp. Click a finished one to reload its text into the textarea.

---

## Auth and rate limiting

- **Login** happens on Keycloak's page (authorization code flow + PKCE, via
  `keycloak-js` in `frontend/src/auth.js`). The browser never sends a password
  to the API. It sends `Authorization: Bearer <access token>` on every call.
- **The API is a resource server** (`config/SecurityConfig.java`). It verifies
  the JWT's signature against Keycloak's public keys, plus its expiry, issuer
  and audience (`capture-to-text-api`). The user id is the token's `sub`
  claim, never a field in the request.
- **Ownership:** `Capture` and `Payment` store `ownerId`, and every query filters
  on it. Another user's capture or payment returns `404`, exactly like a missing
  one. Idempotency keys are unique per user (a compound index on
  `ownerId + idempotencyKey`).
- **Rate limiting** (`ratelimit/`): a token bucket per user in Redis, updated
  atomically by a Lua script. `POST /api/payments` has its own bucket
  (10 burst, 10/min); everything else shares one (120 burst, 120/min). Over
  the limit you get `429` with `Retry-After`. If Redis is down the limiter
  **fails open**, so requests are allowed rather than rejected.
- The Stripe webhook stays public: Stripe can't log in, so it's authenticated
  by its HMAC signature instead.

```bash
# A token from the command line (the load-test client allows the password grant):
TOKEN=$(curl -s -d grant_type=password -d client_id=capture-to-text-loadtest \
  -d username=alice -d password=alice \
  http://localhost:8180/realms/capture-to-text/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/captures
```

---

## API Reference

All `/api/**` endpoints except the Stripe webhook require
`Authorization: Bearer <Keycloak access token>`. Without a valid one you get
`401`, and over the rate limit `429` with `Retry-After`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/captures` | Multipart upload, field name `image`. Uploads to MinIO, saves a `PENDING` `Capture`, publishes to Kafka, and returns immediately (202) — `extractedText`/`ocrConfidence` are `null` until the worker finishes. |
| `GET` | `/api/captures` | The caller's own captures only. Paginated list, newest first. Supports standard Spring pagination params: `?page=0&size=20&sort=createdAt,desc`. Each item's `status` is `PENDING`, `DONE`, or `FAILED`. |
| `GET` | `/api/captures/{id}` | Fetch a single capture by id (404 if not found) — poll this until `status` leaves `PENDING`. Finished captures include `suggestedAmountCents` (or `null`). |
| `POST` | `/api/payments` | **Requires an `Idempotency-Key` header.** JSON body `{captureId?, amountCents, currency, description}`. Returns `201` with `checkoutUrl` for a new payment; `200` (`Idempotent-Replayed: true`) for a retry of the same request; `422` if the key was used with a different body; `409` if the first request is still in progress; `503` + `Retry-After` if Stripe couldn't be reached (outcome unknown: retry with the same key); `502` if Stripe rejected it (payment `FAILED`); `400` if `captureId` isn't one of the caller's captures. Keys are scoped per user. |
| `GET` | `/api/payments/{id}` | The caller's own payment (404 otherwise). Status: `CREATED`, `PENDING`, `SUCCEEDED`, `FAILED`, or `EXPIRED`. |
| `POST` | `/api/webhooks/stripe` | Called by Stripe only. Verifies the `Stripe-Signature` header (`400` if invalid) and applies each event id at most once. |

Error responses are JSON with `timestamp`, `status`, `error`, `message` —
non-image uploads return `400`, missing ids return `404`, oversized uploads
return `413`. OCR failures no longer surface as an HTTP error status (the
request already returned 202) — they land on the `Capture` itself as
`status: "FAILED"` with an `errorMessage`.

Example:
```bash
curl -X POST http://localhost:8080/api/captures -H "Authorization: Bearer $TOKEN" -F "image=@screenshot.png"
```

---

## Project layout

```
capture-to-text/
├── docker-compose.yml               # local Kafka (KRaft, dual listeners), MinIO, Keycloak, Redis
├── config/keycloak/                 # realm import: clients, audience mapper, test users
├── Dockerfile, .dockerignore        # multi-stage build for the API module
├── k8s/                             # API + worker Deployments, API HPA, Service, ConfigMaps, Secret
│   ├── monitoring/                  # Prometheus, Grafana (+ provisioned dashboard), kafka-exporter
│   └── loadtest/                    # k6 Job, script, sample image
├── mvnw, mvnw.cmd, .mvn/            # Maven Wrapper (API module)
├── pom.xml                          # API module
├── src/main/java/com/capturetotext/app/
│   ├── CaptureToTextApplication.java
│   ├── controller/CaptureController.java, PaymentController.java, StripeWebhookController.java
│   ├── service/CaptureService.java    # validation + MinIO upload + Kafka publish
│   ├── service/ImageStorageService.java  # MinIO upload
│   ├── service/PaymentService.java    # idempotent payment creation
│   ├── service/PaymentWebhookService.java  # signed, deduplicated webhook handling
│   ├── service/OutboxRelay.java       # publishes queued payment events to Kafka
│   ├── gateway/                       # PaymentGateway interface + Stripe Checkout implementation
│   ├── dto/                           # payment request/response/event shapes
│   ├── config/                        # MinIO, Stripe properties, Clock, SecurityConfig (JWT resource server)
│   ├── ratelimit/                     # Redis token-bucket limiter + the interceptor that applies it
│   ├── repository/                    # CaptureRepository, PaymentRepository
│   ├── model/                         # Capture, Payment (+ PaymentStatus state machine, OutboxEvent)
│   └── exception/                     # custom exceptions + @RestControllerAdvice
├── src/test/java/...                  # unit tests, test-data builder, Testcontainers integration test
├── config/secrets.properties          # Stripe keys, local only (gitignored)
├── src/main/resources/
│   ├── application.properties
│   └── application-vite.properties    # profile: Stripe redirects to the Vite dev server
├── frontend/                          # React client (Vite, JS); built into the jar by Maven
│   ├── vite.config.js                 # /api proxy for dev, build output → target/classes/static
│   └── src/                           # App.jsx, api.js (all fetch calls + retry), auth.js (Keycloak), components/
│
├── worker/                          # separate deployable — a Kafka consumer (HTTP only for health/metrics)
│   ├── Dockerfile                    # installs libtesseract5 (Tess4J has no Linux natives)
│   ├── mvnw, mvnw.cmd, .mvn/         # its own Maven Wrapper
│   ├── pom.xml
│   └── src/main/java/com/capturetotext/worker/
│       ├── WorkerApplication.java
│       ├── listener/CaptureUploadListener.java   # @KafkaListener — the actual pipeline
│       ├── service/OcrService.java, OcrResult.java   # Tess4J (moved from the API)
│       ├── service/BillAmountExtractor.java          # guesses the amount due from OCR text
│       ├── service/ImageStorageService.java          # MinIO download
│       ├── config/MinioConfig.java
│       ├── repository/CaptureRepository.java
│       └── model/Capture.java, CaptureStatus.java    # own copy, same collection
│
├── CONCEPTS.md                      # deep-dive explanations of every concept used here
└── CLAUDE.md                        # architecture reference + known simplifications
```

## Known limitations

- **No roles (RBAC).** Every logged-in user is equal; there's no admin who can
  see everyone's data. Keycloak realm roles + `@PreAuthorize` would add it.
- **Data from before auth has no owner.** Captures and payments created before
  `ownerId` existed are invisible to everyone. An existing database also still
  has the old global unique index on `idempotencyKey`, so drop it once:
  `db.payments.dropIndex("idempotencyKey")`.
- **Dev-mode Keycloak.** `start-dev` uses an in-container H2 database
  (users created via the registration page are lost when the container is
  removed), plain HTTP and fixed admin credentials.
- **A worker Pod can be "Ready" while consuming nothing.** Its probes only
  check the HTTP server, not the Kafka listener container — if the listener
  dies (this happened when the Linux Tesseract library was missing) the Pod
  stays `1/1 Ready` and uploads sit `PENDING`. The dashboard's Kafka backlog
  and OCR jobs/sec panels are what expose it.
- **The API autoscales on CPU only, and the worker doesn't autoscale.**
  Lag-based scaling for the worker would need KEDA or a custom-metrics adapter.
- **No cancel or timeout.** A `Capture` that never gets picked up (e.g. a
  Kafka publish that silently fails) stays `PENDING` forever — there's no
  cancel endpoint and no expiry sweep.
- **The OCR upload path has no outbox.** Payments do (events are queued in the
  `Payment` document and relayed to Kafka), but a `Capture` is still saved
  *before* its Kafka publish. If that publish fails, the row stays `PENDING`.
- **The outbox is at-least-once.** A relay retry after a timeout, or two API
  replicas polling at the same moment, can publish an event twice. Consumers
  must deduplicate on `eventId`.
- **No refunds.** An abandoned checkout stays `PENDING` until Stripe expires
  the session (30 minutes), then becomes `EXPIRED`.
- **Single MongoDB instance, single Kafka broker.** No replication or
  failover — see `CLAUDE.md` for what each of these would need to survive
  real production traffic.
- **Monitoring endpoints are unauthenticated** (`/actuator/prometheus`,
  Grafana runs with anonymous Admin) — fine on a local cluster reached only via
  `port-forward`, not for anything shared.
- Not yet built: browser extension for direct screen-region capture, Helm
  chart, Ingress/TLS in front of the k8s Service.
