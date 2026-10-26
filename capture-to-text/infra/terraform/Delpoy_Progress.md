# AWS deploy: progress and next step

Learning project: deploy capture-to-text to AWS with Terraform, in stages, with a lesson
before each stage. Every AWS resource comes from Terraform, never from the console or the CLI.

## Stages

| # | Stage | Status |
|---|-------|--------|
| 1 | Everything on one EC2 server (docker compose), reached through Session Manager tunnels | **Done, works end to end** (upload goes PENDING → DONE) |
| 2 | MinIO → S3 | **Next** |
| 3 | Mongo container → MongoDB Atlas free tier (`mongodbatlas` provider) | |
| 4 | HTTPS + Stripe keys/webhooks | |
| 5 | ECS Fargate + ALB, remote state | |

Credit-safe rules for this free-plan account: no EKS, no NAT gateway, and no AWS
Organization or IAM Identity Center (either one upgrades the account to paid). Stop the
server when you're not using it.

## Account / tools

- Login: IAM user `me-admin` (MFA, AdministratorAccess), access key in `~/.aws/credentials`,
  region `ap-southeast-1`. Check it with `aws sts get-caller-identity`.
- Needs Terraform ≥ 1.16, AWS CLI v2, and the Session Manager plugin.

## Stage 1 (`stage1/`)

- One public subnet with an IGW, and **no inbound security group rules**. Access is only
  through Session Manager.
- Role with `AmazonSSMManagedInstanceCore`. Server is m7i-flex.large, IMDSv2 required,
  30 GiB gp3.
- `user_data.sh.tftpl` runs on first boot only. It installs docker plus the compose and buildx
  plugins, clones `github.com/Will2430/Java` (main), then runs
  `docker compose -f docker-compose.yml -f /opt/compose.server.yml up -d --build`.
- `compose.server.yml` points every service at the others by service name. It adds a mongo
  container, and it replaces `minio/minio` (removed from Docker Hub) with
  `chainguard/minio:latest` + `user: root`.
- `user_data_replace_on_change = true`: editing the boot script or `compose.server.yml`
  **replaces the server** on the next apply.
- No Stripe keys, so payments don't work yet. That's expected until stage 4.

Run / use / stop:

```powershell
terraform apply                              # start (or apply changes)
terraform output                             # prints tunnel_api, tunnel_keycloak, shell commands
# run tunnel_api and tunnel_keycloak in two terminals, browse http://localhost:8080 (alice/alice)
terraform apply -var server_running=false    # STOP: keeps the disk, built images and data; only disk is billed
terraform apply                              # start again; containers come back (restart: unless-stopped)
terraform destroy                            # deletes everything (only when done with stage 1 for good)
```

On the server: `sudo tail -n 20 /var/log/cloud-init-output.log`,
`sudo docker compose -p capture-to-text ps` (expect 7 services).

## ⚠ Switching devices: Terraform state is local

`stage1/terraform.tfstate` is gitignored, so it won't arrive with a git pull. Without it,
Terraform on the other device thinks nothing exists, and an apply would create a **second**
VPC and server. Before switching, do one of these:

1. Copy `stage1/terraform.tfstate` to the same path on the other device (privately, not via
   git), or
2. `terraform destroy` here, then apply fresh there (the first boot rebuilds everything, about 10 min).

Remote state (an S3 backend) is planned for stage 5. Moving it earlier would also fix this.

## Plans for stages 2–5 (not built yet)

Each stage starts with a lesson (see "Teaching"), then the code, then you run apply.
Planned as a new folder per stage (`stage2/`, ...), each copied from the previous one.

### Stage 2: MinIO → S3

- Terraform: an S3 bucket (private, encrypted, public access blocked), plus an inline
  policy on the server role that allows only `s3:GetObject/PutObject` on that bucket.
- App: API and worker talk to S3 instead of `http://minio:9000`. Credentials come from the
  role through IMDS, with no keys anywhere. Check whether the MinIO Java client picks those up
  (it has an IAM-role credentials provider) or whether the code should switch to the AWS SDK.
- Open: who creates the bucket? Terraform should own it, but
  `ImageStorageService.ensureBucketExists` also creates it. Likely: make that a check only,
  or turn it off by config.
- Remove the minio service from `compose.server.yml`.
- Option: start remote state here (an S3 backend with `use_lockfile = true`, no DynamoDB
  needed). That fixes the device-switching problem above sooner than stage 5.

### Stage 3: Mongo → MongoDB Atlas free tier (M0)

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

### Stage 4: HTTPS + Stripe

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
- Remote state, if it wasn't done in stage 2.

## Teaching

- Published lessons: 0016 Terraform first contact
  (https://claude.ai/artifact/WAMVq4YjTydVMuGz2Miwp6), 0017 One server in a VPC
  (https://claude.ai/artifact/EySFyRLTkPhHcHuSfgQHaJ). The lesson sources and the teaching
  rules (`NOTES.md`) live in the separate `Learning/` folder, which isn't in this repo.
- Use the same AWS terms as "AWS Foundations: the KedaiPay story"
  (https://claude.ai/artifact/Uz8oRDzCAzKrxidnn3hkX2): local line, IGW line, borrow the role,
  trust vs permissions policy, instance metadata service.
