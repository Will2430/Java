# Capture to Text

Upload or drag-drop an image; OCR (Tesseract, via Tess4J) runs asynchronously
in a separate worker process, decoupled from the upload request by Kafka.
Every capture is saved to MongoDB so you can browse history, and image bytes
live in MinIO (S3-compatible) so the worker can reach them from its own
process. The API module is containerized and deployable to a local
Kubernetes cluster (minikube); the worker currently still runs as a plain
process (see [Project layout](#project-layout) / [Known limitations](#known-limitations)).

**Architecture:**
```
Browser (static HTML/JS)
  → Spring Boot API → MongoDB (capture metadata) + MinIO (image bytes)
      → Kafka topic "capture-uploads"
          → Worker → Tess4J (OCR) → MongoDB (result)
```
The API runs either as a plain `mvnw spring-boot:run` process or as a
Kubernetes Deployment (`k8s/`) — same code, same image, different host for
reaching Mongo/Kafka/MinIO (`localhost` locally, `host.minikube.internal`
from inside a Pod). The worker only runs as a plain process today.

The API returns `202 Accepted` immediately; the client polls
`GET /api/captures/{id}` until `status` leaves `PENDING`. No auth yet.

---

## Prerequisites

| Tool | Version used in this project | Required? |
|---|---|---|
| JDK | 17+ (built/tested with Temurin 21) | Yes |
| MongoDB Community Server | tested with 8.3.4, default port 27017 | Yes, running locally |
| Docker (with Compose) | any recent version | Yes — runs Kafka + MinIO locally, and builds the API's image |
| Maven | **not required** — the bundled Maven Wrapper (`mvnw` / `mvnw.cmd`) downloads Maven 3.9.9 automatically on first run | No |
| Tesseract OCR | **not required as a separate install on Windows/Linux/macOS** — see note below | No |
| minikube + `kubectl` | tested with minikube v1.39.0, Kubernetes v1.37.0 | Only if deploying the API to Kubernetes — see [step 4](#4-optional-deploy-the-api-to-kubernetes) |

### About Tesseract / Tess4J

This project uses [Tess4J](https://github.com/nguyenq/tess4j), a JNA wrapper
around the native Tesseract + Leptonica libraries. Tess4J's Maven artifact
**bundles the native binaries and the English (`eng`) language data** for
Windows, Linux, and macOS, and `worker/`'s `OcrService` extracts them to a
temp directory at startup (`LoadLibs.extractTessResources("tessdata")`). You
do **not** need to install Tesseract separately or download
`eng.traineddata` yourself. Only the worker needs this — the API module
doesn't run OCR anymore.

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

## 2. Start Kafka + MinIO

From the project root (`capture-to-text/`):
```bash
docker compose up -d
```
This starts a single-broker Kafka (KRaft mode, no separate Zookeeper) and
MinIO (S3-compatible object storage) on `localhost:9000` (console on
`localhost:9001`, login `minioadmin` / `minioadmin`). The API creates its
`captures` bucket automatically on startup — no manual MinIO setup needed.
`capture-uploads`, the Kafka topic, is created automatically on first
publish with 3 partitions (see `docker-compose.yml`).

Kafka exposes **two listeners**: `localhost:9092` for processes running
directly on the host (the API/worker via `mvnw`), and `localhost:29092`
(advertised inside the cluster as `host.minikube.internal:29092`) for a
Pod running the containerized API — see [step 4](#4-optional-deploy-the-api-to-kubernetes).
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

---

## 4. (Optional) Deploy the API to Kubernetes

The API module can run as a Kubernetes Deployment instead of a plain
process — the worker still needs to run locally either way (it isn't
containerized yet).

```powershell
# Start the cluster
minikube start --driver=docker

# Build the image and load it into minikube's own container runtime
# (separate from Docker Desktop's image store — a build alone isn't enough)
docker build -t capture-to-text-api:local .
minikube image load capture-to-text-api:local

# Apply the manifests (Deployment, Service, ConfigMap, Secret)
kubectl apply -f k8s/

# Verify
kubectl get pods -l app=capture-api      # expect 1/1 Running
kubectl port-forward svc/capture-api 8080:8080
# then open http://localhost:8080
```

`kubectl port-forward` is the reliable way to reach it locally — on
Windows with the docker driver, `minikube service --url` has to hold a
terminal open as a tunnel for the life of the connection, which
`port-forward` doesn't need. See `k8s/configmap.yaml` for how the Pod
reaches Mongo/Kafka/MinIO on the host (`host.minikube.internal`, not
`localhost` — inside a Pod, `localhost` means the Pod itself).

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

## API Reference

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/captures` | Multipart upload, field name `image`. Uploads to MinIO, saves a `PENDING` `Capture`, publishes to Kafka, and returns immediately (202) — `extractedText`/`ocrConfidence` are `null` until the worker finishes. |
| `GET` | `/api/captures` | Paginated list, newest first. Supports standard Spring pagination params: `?page=0&size=20&sort=createdAt,desc`. Each item's `status` is `PENDING`, `DONE`, or `FAILED`. |
| `GET` | `/api/captures/{id}` | Fetch a single capture by id (404 if not found) — poll this until `status` leaves `PENDING`. |

Error responses are JSON with `timestamp`, `status`, `error`, `message` —
non-image uploads return `400`, missing ids return `404`, oversized uploads
return `413`. OCR failures no longer surface as an HTTP error status (the
request already returned 202) — they land on the `Capture` itself as
`status: "FAILED"` with an `errorMessage`.

Example:
```bash
curl -X POST http://localhost:8080/api/captures -F "image=@screenshot.png"
```

---

## Project layout

```
capture-to-text/
├── docker-compose.yml               # local Kafka (KRaft, dual listeners) + MinIO
├── Dockerfile, .dockerignore        # multi-stage build for the API module
├── k8s/                             # Deployment, Service, ConfigMap, Secret
├── mvnw, mvnw.cmd, .mvn/            # Maven Wrapper (API module)
├── pom.xml                          # API module
├── src/main/java/com/capturetotext/app/
│   ├── CaptureToTextApplication.java
│   ├── controller/CaptureController.java
│   ├── service/CaptureService.java    # validation + MinIO upload + Kafka publish
│   ├── service/ImageStorageService.java  # MinIO upload
│   ├── config/MinioConfig.java
│   ├── repository/CaptureRepository.java
│   ├── model/Capture.java, CaptureStatus.java   # MongoDB @Document
│   └── exception/                     # custom exceptions + @RestControllerAdvice
├── src/main/resources/
│   ├── application.properties
│   └── static/index.html              # the entire client (vanilla HTML/JS)
│
├── worker/                          # separate deployable — no HTTP, just a Kafka consumer
│   ├── mvnw, mvnw.cmd, .mvn/         # its own Maven Wrapper
│   ├── pom.xml
│   └── src/main/java/com/capturetotext/worker/
│       ├── WorkerApplication.java
│       ├── listener/CaptureUploadListener.java   # @KafkaListener — the actual pipeline
│       ├── service/OcrService.java, OcrResult.java   # Tess4J (moved from the API)
│       ├── service/ImageStorageService.java          # MinIO download
│       ├── config/MinioConfig.java
│       ├── repository/CaptureRepository.java
│       └── model/Capture.java, CaptureStatus.java    # own copy, same collection
│
├── CONCEPTS.md                      # deep-dive explanations of every concept used here
└── CLAUDE.md                        # architecture reference + known simplifications
```

## Known limitations

- **No auth.** Anyone reaching the API can upload and browse all captures.
- **The worker isn't containerized or deployed to Kubernetes.** It still
  runs as a plain `mvnw spring-boot:run` process — the API is the only
  module with a `Dockerfile`/k8s manifests today.
- **No cancel or timeout.** A `Capture` that never gets picked up (e.g. a
  Kafka publish that silently fails) stays `PENDING` forever — there's no
  cancel endpoint and no expiry sweep.
- **No outbox pattern.** The `Capture` row is saved to MongoDB *before* the
  Kafka publish; if the publish then fails, the row is already committed as
  `PENDING` with no rollback.
- **Single MongoDB instance, single Kafka broker.** No replication or
  failover — see `CLAUDE.md` for what each of these would need to survive
  real production traffic.
- Not yet built: browser extension for direct screen-region capture, Helm
  chart, Ingress/TLS in front of the k8s Service.
