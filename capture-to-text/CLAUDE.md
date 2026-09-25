# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

"Capture-to-text" OCR tool: upload/drag-drop/paste an image in a single-page
vanilla HTML/JS client. Phase 2: OCR now runs asynchronously — a Spring Boot
API accepts the upload, stores the image in MinIO, and publishes a job to
Kafka; a separate worker process (own module, own JVM, no shared code with
the API beyond duplicated entity classes) consumes it, runs Tess4J, and
writes the result back to MongoDB. The client polls for completion. No auth
yet; the API/worker JVM processes themselves aren't containerized (only
their infra — Kafka, MinIO — runs in Docker via `docker-compose.yml`).

## Commands

There are now **two independent Maven projects** — the API at the repo root
and the worker in `worker/` — each with its own wrapper, own `pom.xml`, own
`spring-boot:run`. Run both to exercise the full pipeline.

```powershell
# Set once per shell if JAVA_HOME isn't already set — mvnw.cmd fails without it
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"

# Infra (Kafka + MinIO) -- from the repo root
docker compose up -d

# API -- from the repo root, http://localhost:8080
.\mvnw.cmd spring-boot:run

# Worker -- from worker/, no HTTP port (Kafka consumer only)
cd worker
.\mvnw.cmd spring-boot:run

.\mvnw.cmd clean compile            # compile only, fastest way to check for errors
.\mvnw.cmd clean package            # build target\capture-to-text-0.1.0.jar (or -worker-0.1.0.jar in worker/)
java -jar target\capture-to-text-0.1.0.jar   # run the packaged jar

.\mvnw.cmd test                     # run all tests (no test classes exist yet)
.\mvnw.cmd test -Dtest=ClassName    # run a single test class, once one exists
```

Unix equivalents use `./mvnw` instead of `.\mvnw.cmd` and don't need
`JAVA_HOME` set manually if it's already on the shell's environment.

There is no local `mvn` install requirement — each module's Maven Wrapper
(`mvnw`) downloads Maven 3.9.9 itself on first run via
`.mvn/wrapper/maven-wrapper.properties`.

**MongoDB must be running** before starting either process —
`spring.data.mongodb.uri=mongodb://localhost:27017/capturetotext` in both
modules' `application.properties` (same database, two independent
`Capture`/`CaptureRepository` copies — see Architecture below). On this dev
machine it runs as the Windows service `MongoDB` (`Start-Service MongoDB` /
`Get-Service MongoDB`). No manual schema/collection setup is needed — Spring
Data creates the `capturetotext` database and `captures` collection on
first write.

**Kafka + MinIO must be running** before starting either process —
`docker compose up -d` from the repo root brings up both (see
`docker-compose.yml`). The API creates its MinIO bucket on startup
(`ImageStorageService.ensureBucketExists`); the Kafka topic
`capture-uploads` is created automatically on first publish, with 3
partitions (`KAFKA_NUM_PARTITIONS` in the compose file — deliberately not
1, so running two worker instances later actually demonstrates partition
assignment instead of it being invisible).

**No Tesseract install is required.** Tess4J's Maven artifact bundles native
Tesseract/Leptonica binaries and `eng.traineddata` for Windows/Linux/macOS,
extracted at runtime to a temp dir. Only `worker/` depends on Tess4J now —
see the OcrService gotcha below before touching OCR setup code.

## Architecture

Two independent processes now, coordinating only through MongoDB (shared
`captures` collection) and Kafka (`capture-uploads` topic) — no shared Java
code between them.

**API module** (repo root, `com.capturetotext.app`) — strict layered flow,
each layer only calling the one directly below it:

```
CaptureController (HTTP/JSON concerns only)
  → CaptureService (validation + orchestration; no HTTP, Mongo-driver, or OCR knowledge)
      → ImageStorageService (MinIO upload — image bytes out of the request lifecycle)
      → KafkaTemplate (publish captureId to "capture-uploads")
      → CaptureRepository (Spring Data MongoRepository — no custom queries yet)
```

- `CaptureController` never imports anything OCR-, MinIO-, or Kafka-related;
  it only knows about `Capture` and `CaptureService`. Keep new HTTP concerns
  (new endpoints, request params) here, not in the service.
- `CaptureService.submitForProcessing` does image validation
  (empty/content-type check), uploads the file to MinIO via
  `ImageStorageService`, saves a `Capture` with `status = PENDING`, and
  publishes the saved id to Kafka. It does **not** run OCR — that moved to
  `worker/` entirely (`OcrService`/`OcrResult` were deleted from this
  module, not duplicated).
- The controller returns `202 Accepted`, not `201 Created` —
  `extractedText`/`ocrConfidence` are `null` at response time; they only
  exist once the worker finishes.
- Errors are translated to typed exceptions (`InvalidImageException`,
  `CaptureNotFoundException`, `ImageStorageException`) in the service layer
  and mapped to HTTP status codes centrally in
  `exception/GlobalExceptionHandler.java` (`@RestControllerAdvice`).
  `ImageStorageException` has no dedicated handler — it falls through to the
  generic 500, since a MinIO outage is an infra failure, not bad client
  input. Add new failure modes as new exception types + a handler method
  there, not as inline `ResponseEntity` construction in a controller.
- The client (`src/main/resources/static/index.html`) is served as a Spring
  Boot static resource on the same origin as the API — deliberately, to
  avoid needing any CORS configuration. If the client is ever split out to
  a separately-hosted static site, CORS config will need to be added.

**Worker module** (`worker/`, `com.capturetotext.worker`) — a plain Spring
context, no `spring-boot-starter-web`, so no HTTP port and no controller
layer at all; its entire job is one `@KafkaListener`:

```
CaptureUploadListener (@KafkaListener on "capture-uploads", group "ocr-workers")
  → ImageStorageService (MinIO download — object key → local temp File)
  → OcrService (Tess4J integration — image File in, OcrResult out)
  → CaptureRepository (own copy — same "captures" collection as the API)
```

- `CaptureUploadListener.handleUpload(String captureId)` re-fetches the
  `Capture` row by id (the Kafka message carries nothing else — the DB is
  the single source of truth), downloads the image, runs OCR, and writes
  `extractedText`/`ocrConfidence`/`status = DONE` back — or
  `status = FAILED` + `errorMessage` on any exception. The `try`/`catch`/
  `finally` shape (`TesseractException`, `IOException`, temp-file cleanup)
  is deliberately the same as the API's old (Phase 1) synchronous path —
  only the trigger and the outcome changed, not the error handling.
- `worker/model/Capture.java` and `worker/repository/CaptureRepository.java`
  are **intentional duplicates** of the API's — this is the real cost of two
  genuinely independent deployables with no shared library, not an
  oversight. If a third consumer of `Capture` ever appears, that's the
  signal to extract a shared module; not before.

### Tess4J version-specific gotchas (do not "fix" these back)

`worker/src/main/java/com/capturetotext/worker/service/OcrService.java`
deviates from Tess4J's own wiki sample code in two places, verified by
direct testing against the actual bundled `tess4j 5.19.0` jar:

1. `LoadLibs` is imported from `net.sourceforge.tess4j.util.LoadLibs`, not
   the base `net.sourceforge.tess4j` package (the wiki samples show the
   latter, which doesn't compile against this version).
2. `tesseract.setDatapath(...)` is passed
   `LoadLibs.extractTessResources("tessdata").getAbsolutePath()` — the
   `tessdata` folder itself — not `.getParent()` as the wiki sample shows.
   Passing the parent throws `IllegalArgumentException: Specified language
   data does not exist` at OCR time in this version.

If `tess4j.version` in `pom.xml` is ever bumped, re-verify both of these
against the new jar before trusting upstream examples; they are not stable
across versions. See `CONCEPTS.md` §10 for how these were originally found.

### Confidence score

`ocrConfidence` is not a single value Tesseract exposes directly — it's
computed in the worker's `OcrService.tryComputeConfidence` by averaging
per-word confidences from
`tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD)`. This is
wrapped in its own try/catch returning `null` on failure, separate from the
main `doOCR` call, since text extraction is the critical path and confidence
is best-effort (`ocrConfidence` is nullable in the API contract regardless).

### Pagination

`GET /api/captures` uses Spring Data Web's `Pageable`/`@PageableDefault`
binding (`page`, `size`, `sort` query params) and returns a raw
`Page<Capture>` — the full Spring Data page envelope (`content`,
`totalElements`, `totalPages`, etc.), not a custom DTO. The client's
`loadHistory()` in `index.html` reads `page.content`; each item's `status`
(`PENDING`/`DONE`/`FAILED`) is what drives the "(processing...)" placeholder
and the polling loop in `uploadImage()`.

### Async pipeline (Kafka + MinIO)

- **Topic:** `capture-uploads`, message value is just the `captureId`
  (`String`/`String` key-value serde — no JSON envelope, since the DB holds
  everything else). Consumer group `ocr-workers`.
- **Delivery semantics:** default Spring Kafka ack mode (offset committed
  after the listener method returns without throwing) — if the worker
  crashes mid-`handleUpload`, the message is redelivered on restart and the
  capture still completes; this is deliberate, not accidental durability
  (see `Learning/lessons/0006-kafka-fundamentals.html`).
- **`CaptureUploadListener.handleUpload` swallows its own exceptions** —
  `TesseractException`/`IOException` are caught and turned into
  `status = FAILED` + `errorMessage`, then the method returns normally. This
  means a bad *image* doesn't get redelivered forever (it's a permanent
  failure, not a transient one) — only a crash of the worker process itself
  triggers redelivery. If a genuinely transient failure mode is added later
  (e.g. MinIO temporarily unreachable), reconsider whether it should
  actually rethrow instead of being caught here.
- If `spring.kafka.bootstrap-servers` is unreachable when the API tries to
  publish, `KafkaTemplate.send` surfaces as an unchecked exception, caught
  by `GlobalExceptionHandler`'s generic 500 handler — the `Capture` row
  will already have been saved as `PENDING` by that point and will stay
  stuck there (no outbox/rollback pattern yet — a known simplification).
  This is also the silent-failure mode for a *reachable-but-misconfigured*
  broker: `KafkaTemplate.send` doesn't block on delivery, so even an
  eventual producer failure (not just an unreachable broker) never updates
  the row — see the k8s listener note below for a real instance of this.

### Reaching Kafka from inside a Pod (`k8s/`)

`docker-compose.yml`'s Kafka container runs **two listeners**, not one:
`PLAINTEXT` on 9092 (advertised as `localhost`, for the worker/API running
directly on the host) and `PLAINTEXT_HOST` on 29092 (advertised as
`host.minikube.internal`, for `capture-api` running inside a minikube Pod).
This exists because Kafka's client protocol is a two-hop handshake — the
initial bootstrap connection fetches metadata containing the broker's
*advertised* address, and every subsequent connection (including the actual
produce/consume traffic) targets that advertised address, not the one the
client originally dialed. A single `localhost`-advertised listener works
fine for host processes but sends a Pod's producer into a loop trying to
reconnect to itself — the symptom is captures stuck permanently at
`PENDING` with continuous `Connection to node 1 (localhost/127.0.0.1:9092)
could not be established` in the pod's logs, not an exception anywhere
visible in the API's own error handling (see the note above on why).
`k8s/configmap.yaml`'s `SPRING_KAFKA_BOOTSTRAP_SERVERS` must point at
`host.minikube.internal:29092`, not `:9092`, for this reason.

## Reference docs in this repo

- `README.md` — setup/run instructions and full API reference (request/response shapes, error status codes).
- `CONCEPTS.md` — line-by-line educational explanation of every Java/Maven/Spring/MongoDB/Tess4J concept used in this codebase, written for someone learning the stack. Read it if unsure why something is structured a particular way.
