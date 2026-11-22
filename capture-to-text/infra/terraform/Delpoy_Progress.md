# AWS deploy: progress and next step

Learning project: deploy capture-to-text to AWS with Terraform, in stages, with a lesson
before each stage. Every AWS resource comes from Terraform, never from the console or the CLI.
All the Terraform lives in this one folder and grows with each stage. There are no per-stage
copies, so `git log` and the notes below are the history.

## Stages

| # | Stage | Status |
|---|-------|--------|
| 1 | Everything on one EC2 server (docker compose), reached through Session Manager tunnels | **Done, works end to end** (upload goes PENDING → DONE) |
| 2 | MinIO → S3 | **Done, applied and verified** (upload lands in the S3 bucket). Lesson 0018: https://claude.ai/artifact/VQ2Bm7CfFmn53wE4aMvwZA |
| 3 | Mongo container → MongoDB Atlas free tier (`mongodbatlas` provider) | **Code written; first apply failed** (Atlas `401 Unauthorized` on the project, after the old server had already been destroyed). Lesson 0019: https://claude.ai/artifact/XwQNLi3Joxt1uzRSka9vJy |
| 4 | HTTPS + Stripe keys/webhooks | **Code written, not applied** (AWS account suspended for payment verification, 2026-11-22). Applies together with stage 3. Lesson 0020: https://claude.ai/artifact/3PD8BMNAkLz2qnNKMXRzTu |
| 5 | ECS Fargate + ALB, remote state | |

Credit-safe rules for this free-plan account: no EKS, no NAT gateway, and no AWS
Organization or IAM Identity Center (either one upgrades the account to paid). Stop the
server when you're not using it.

## Account / tools

- Login: IAM user `me-admin` (MFA, AdministratorAccess), access key in `~/.aws/credentials`,
  region `ap-southeast-1`. Check it with `aws sts get-caller-identity`.
- Needs Terraform ≥ 1.16, AWS CLI v2, and the Session Manager plugin.

## Stage 1: what it added

- One public subnet with an IGW, and **no inbound security group rules**. Access is only
  through Session Manager.
- Role with `AmazonSSMManagedInstanceCore`. Server is m7i-flex.large, IMDSv2 required,
  30 GiB gp3.
- `user_data.sh.tftpl` runs on first boot only. It installs docker plus the compose and buildx
  plugins, clones `github.com/Will2430/Java` (main), then runs
  `docker compose -f docker-compose.yml -f /opt/compose.server.yml up -d --build`.
- `compose.server.yml` points every service at the others by service name and adds a mongo
  container. In stage 1 it also swapped `minio/minio` (removed from Docker Hub) for
  `chainguard/minio:latest`; stage 2 removed that.
- `user_data_replace_on_change = true`: editing the boot script or `compose.server.yml`
  **replaces the server** on the next apply.
- No Stripe keys, so payments don't work yet. That's expected until stage 4.

Run / use / stop:

⚠ Every `apply`, including the stop below, also applies any code that hasn't been applied
yet. That's how the stop on 2026-11-16 replaced (deleted) the server for stage 3. Run
`terraform plan` first and read it.

```powershell
terraform apply                              # start (or apply changes)
terraform output                             # from stage 4: app_url, stripe_webhook_url, shell
# browse the app_url (alice/alice); the tunnels were removed in stage 4
terraform apply -var server_running=false    # STOP: keeps the disk, built images and data; disk + Elastic IP are billed
terraform apply                              # start again; containers come back (restart: unless-stopped)
terraform destroy                            # deletes everything, including the S3 bucket and its images
```

On the server: `sudo tail -n 20 /var/log/cloud-init-output.log`,
`sudo docker compose -p capture-to-text ps` (6 services from stage 4: kafka, keycloak, redis,
caddy, api, worker; no minio, no mongo).

## ⚠ Switching devices: Terraform state is local

`terraform.tfstate` (in this folder) is gitignored, so it won't arrive with a git pull. Without it,
Terraform on the other device thinks nothing exists, and an apply would create a **second**
VPC and server. Before switching, do one of these:

1. Copy `terraform.tfstate` to the same path on the other device (privately, not via
   git), or
2. `terraform destroy` here, then apply fresh there (the first boot rebuilds everything, about 10 min).

Remote state (an S3 backend) is planned for stage 5. Moving it earlier would also fix this.

## Stage 2 and the plans for 3–5

Each stage starts with a lesson (see "Teaching"), then the code, then you run apply. New
stages edit and add to the files here.

### Stage 2: MinIO → S3 (done, see lesson 0018)

- Added: `storage.tf` (bucket named after the account id, `force_destroy`, public access
  block), an inline policy in `iam.tf` (ListBucket on the bucket, Put/GetObject on `/*`),
  and `http_put_response_hop_limit = 2`. `compose.server.yml` became a template (MinIO behind
  an unused profile, empty `MINIO_ACCESS_KEY` → `MinioConfig` uses `IamAwsProvider`).
- Decision: Terraform owns the bucket; the API's `ensureBucketExists` only finds it.
- To run: push the `MinioConfig.java` change to main, then `terraform apply` here. Plan: 5 to
  add, 8 to change (tags), 2 to destroy. The VPC stays; the server is replaced, so stage 1's
  test captures in Mongo are lost. Expect 6 services.
- Option: start remote state next (an S3 backend with `use_lockfile = true`, no DynamoDB
  needed). That fixes the device-switching problem above sooner than stage 5.

### Stage 3: Mongo → MongoDB Atlas free tier (M0) (code written, see lesson 0019)

What was built (plan below kept for the reasoning):
- `database.tf`: Atlas project + M0 cluster (`AP_SOUTHEAST_1`), `aws_eip` on the server, an
  access-list entry for that IP, database user `capture-to-text-app` (readWrite on
  `capturetotext`), SSM SecureString `/capture-to-text/mongo-uri`. The password is an
  `ephemeral "random_password"` passed only to write-only args (`password_wo`, `value_wo`), so
  it is never in state. To rotate it, bump BOTH `*_wo_version` to 2 together.
- `iam.tf`: `ssm:GetParameter` on that parameter. Boot script writes `/opt/mongo.env` (root
  only, `set +x`); api and worker load it with `env_file`. mongo container and volume removed.
- Every Terraform command now needs `$env:MONGODB_ATLAS_CLIENT_ID` and
  `$env:MONGODB_ATLAS_CLIENT_SECRET` set, and `terraform.tfvars` with `atlas_org_id`.
- No push needed (no file the server clones changed). Plan: about 9 to add, 0 to change,
  2 to destroy. Expect 5 services. Only `validate` has run so far.

Original plan:

- Terraform: `mongodbatlas` provider with an Atlas API key (a programmatic key, kept out of
  git). Creates the project, an M0 cluster in AWS `ap-southeast-1`, a database user, and an
  IP access-list entry.
- M0 has no private link or peering, so Atlas is reached over the internet. Allow only the
  server's IP. The public IP changes on every stop/start, so add an Elastic IP (`aws_eip`)
  or the access list goes stale.
- The connection string is a secret. Store it in SSM Parameter Store (SecureString), let the
  role read it, and fetch it at boot. Not in user_data: anything on the server can read that
  through IMDS.
- Remove the mongo service. Atlas is a replica set, so multi-document transactions become
  possible, but keep the embedded outbox as it is.

### Stage 4: HTTPS + Stripe (code written, see lesson 0020)

What was built:
- Address: free **sslip.io** names made from the Elastic IP (`app.13-229-x-x.sslip.io`,
  `auth.…`). Nothing to buy. `locals` in `web.tf`. The EIP is now its own resource plus an
  `aws_eip_association`, because the boot script needs the IP before the server exists.
- `web.tf`: inbound 80 + 443 rules, and two SSM SecureStrings
  (`/capture-to-text/stripe-secret-key`, `/capture-to-text/stripe-webhook-secret`) created
  with the placeholder `set-me`. You put the real values in with the CLI; Terraform never
  overwrites them (write-only value, version stays 1).
- Caddy container (`caddy:2.10`, `Caddyfile.tftpl`): HTTPS certificates, app host → api:8080,
  auth host → keycloak:8080, with `/admin*` and `/realms/master*` returning 404.
- Keycloak: `KC_HOSTNAME=https://auth…`, `KC_PROXY_HEADERS=xforwarded`. The boot script
  `sed`s the realm's `http://localhost:8080` to `https://app…`.
- API: `ISSUER_URI` and `APP_PUBLIC_BASE_URL` set to the public names. The `Dockerfile` now
  takes `ARG VITE_KEYCLOAK_URL` for the React build (**needs a push to main**).
- Secrets: `refresh_secrets.sh.tftpl` → `/opt/refresh-secrets.sh` writes `/opt/secrets.env`
  (Mongo URI + both Stripe keys) and runs `docker compose up`. Rerun it after changing a
  secret. `iam.tf` policy renamed `app_secrets`, covering all three parameters.
- Outputs: tunnels removed; `app_url` and `stripe_webhook_url` added.
- Cost: the Elastic IP is billed (about $3.60/month) even while the server is stopped.
- Not reachable any more: the Keycloak admin console (blocked in Caddy, and the tunnel would
  redirect to the public name).

Run order (once the AWS account works again and the Atlas 401 is fixed):
1. Push the `Dockerfile` change to main.
2. `terraform plan`: about 16 to add, 2 to destroy (the security group and its egress rule are
   replaced, because the group's description changed). Then `terraform apply`. First boot: about 10 min.
3. `terraform output stripe_webhook_url`. In the Stripe dashboard (test mode), add a webhook
   endpoint with that URL and the four events `checkout.session.completed`, `.expired`,
   `.async_payment_succeeded`, `.async_payment_failed`. Copy its signing secret (`whsec_…`).
4. Put both secrets in SSM. `Read-Host` keeps the value out of PowerShell's history:
   ```powershell
   $v = Read-Host "Stripe secret key (sk_test_...)"
   aws ssm put-parameter --name /capture-to-text/stripe-secret-key --type SecureString --overwrite --value $v
   $v = Read-Host "Webhook signing secret (whsec_...)"
   aws ssm put-parameter --name /capture-to-text/stripe-webhook-secret --type SecureString --overwrite --value $v
   Remove-Variable v
   ```
5. Server shell (`terraform output shell`): `sudo /opt/refresh-secrets.sh`.
6. Check: open `app_url` (padlock, no warning) → alice/alice → upload → DONE → pay with
   card `4242 4242 4242 4242` → payment SUCCEEDED. Stripe's webhook page shows a 200 delivery.
   `sudo docker compose -p capture-to-text logs caddy | grep -i "certificate obtained"`.

Original plan:

- Needs a public HTTPS address: Stripe webhooks must reach the server, and Keycloak
  (`sslRequired: external`) needs HTTPS off localhost. Pick a domain (Route 53 hosted zone
  about $0.50/month, plus the domain itself).
- Likely shape: Caddy (a reverse proxy, the program that receives web traffic and passes it
  to the app, which also gets Let's Encrypt certificates on its own) on the server. Open
  80/443 in the security group. Behind it: `/` → api:8080, `auth.<domain>` → keycloak:8080.
- Everything that says `localhost:8180` / `localhost:8080` must change together:
  `KC_HOSTNAME`, `VITE_KEYCLOAK_URL` (build time), realm `redirectUris`/`webOrigins`, the
  API `issuer-uri`, and Stripe success/cancel URLs.
- Stripe test keys and the webhook secret (`whsec_...`) go in SSM Parameter Store. Register
  the webhook endpoint `https://<domain>/api/webhooks/stripe` in the Stripe dashboard.

### Stage 5: ECS Fargate + ALB, remote state

- ECR repositories for the api and worker images. Build and push from the PC (CI later).
- ECS cluster plus Fargate services for the api and worker, with task roles that replace the
  EC2 role. ALB with an HTTPS listener (ACM certificate) in front of the api. The ALB costs
  about $16+/month, which comes out of credits.
- No NAT: run tasks in public subnets with a public IP, with security groups allowing in only
  the ALB.
- Open: where Kafka, Keycloak and Redis live. Options are keeping them on the EC2 server,
  running them as Fargate services (needs storage for Kafka), or managed services (MSK and
  ElastiCache are expensive for this account). Decide in that stage's lesson.
- Remote state, if it wasn't done earlier.

## Teaching

- Published lessons: 0016 Terraform first contact
  (https://claude.ai/artifact/WAMVq4YjTydVMuGz2Miwp6), 0017 One server in a VPC
  (https://claude.ai/artifact/EySFyRLTkPhHcHuSfgQHaJ), 0018 Images in S3
  (https://claude.ai/artifact/VQ2Bm7CfFmn53wE4aMvwZA), 0019 Data in Atlas
  (https://claude.ai/artifact/XwQNLi3Joxt1uzRSka9vJy), 0020 A public front door (https://claude.ai/artifact/3PD8BMNAkLz2qnNKMXRzTu). The lesson sources and the teaching
  rules (`NOTES.md`) live in the separate `Learning/` folder, which isn't in this repo.
- Use the same AWS terms as "AWS Foundations: the KedaiPay story"
  (https://claude.ai/artifact/Uz8oRDzCAzKrxidnn3hkX2): local line, IGW line, borrow the role,
  trust vs permissions policy, instance metadata service.
