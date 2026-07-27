# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Phase 1 MVP of a "capture-to-text" OCR tool: upload/drag-drop/paste an image
in a single-page vanilla HTML/JS client, extract text synchronously via
Tess4J (Tesseract OCR), persist the result to MongoDB, browse history.
Architecture is intentionally minimal — no queue, no worker service, no
auth, no Docker. A Phase 2 (Kafka-based async worker) is planned but not
started; do not add it unprompted.

## Commands

All commands run from the project root (this directory).

```powershell
# Set once per shell if JAVA_HOME isn't already set — mvnw.cmd fails without it
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"

.\mvnw.cmd spring-boot:run          # run the app (dev mode) — http://localhost:8080
.\mvnw.cmd clean compile            # compile only, fastest way to check for errors
.\mvnw.cmd clean package            # build target\capture-to-text-0.1.0.jar
java -jar target\capture-to-text-0.1.0.jar   # run the packaged jar

.\mvnw.cmd test                     # run all tests (no test classes exist yet)
.\mvnw.cmd test -Dtest=ClassName    # run a single test class, once one exists
```

Unix equivalents use `./mvnw` instead of `.\mvnw.cmd` and don't need
`JAVA_HOME` set manually if it's already on the shell's environment.

There is no local `mvn` install requirement — the Maven Wrapper (`mvnw`)
downloads Maven 3.9.9 itself on first run via
`.mvn/wrapper/maven-wrapper.properties`.

**MongoDB must be running** before starting the app —
`spring.data.mongodb.uri=mongodb://localhost:27017/capturetotext` in
`src/main/resources/application.properties`. On this dev machine it runs as
the Windows service `MongoDB` (`Start-Service MongoDB` / `Get-Service MongoDB`).
No manual schema/collection setup is needed — Spring Data creates the
`capturetotext` database and `captures` collection on first write.

**No Tesseract install is required.** Tess4J's Maven artifact bundles native
Tesseract/Leptonica binaries and `eng.traineddata` for Windows/Linux/macOS,
extracted at runtime to a temp dir. See the OcrService gotcha below before
touching OCR setup code.

## Architecture

Strict three-layer flow, each layer only calling the one directly below it:

```
CaptureController (HTTP/JSON concerns only)
  → CaptureService (validation + orchestration; no HTTP or Mongo-driver knowledge)
      → OcrService (Tess4J integration — image File in, OcrResult out)
      → CaptureRepository (Spring Data MongoRepository — no custom queries yet)
```

- `CaptureController` never imports anything OCR- or Mongo-related; it only
  knows about `Capture` and `CaptureService`. Keep new HTTP concerns (new
  endpoints, request params) here, not in the service.
- `CaptureService.processAndSave` does image validation (empty/content-type
  check), writes the upload to a temp `File` (Tess4J needs a real file, not
  a stream), calls `OcrService`, builds/saves the `Capture`, and always
  deletes the temp file in a `finally` block.
- Errors are translated to typed exceptions (`InvalidImageException`,
  `OcrProcessingException`, `CaptureNotFoundException`) in the service layer
  and mapped to HTTP status codes centrally in
  `exception/GlobalExceptionHandler.java` (`@RestControllerAdvice`). Add new
  failure modes as new exception types + a handler method there, not as
  inline `ResponseEntity` construction in a controller.
- The client (`src/main/resources/static/index.html`) is served as a Spring
  Boot static resource on the same origin as the API — deliberately, to
  avoid needing any CORS configuration. If the client is ever split out to
  a separately-hosted static site, CORS config will need to be added.

### Tess4J version-specific gotchas (do not "fix" these back)

`OcrService.java` deviates from Tess4J's own wiki sample code in two places,
verified by direct testing against the actual bundled `tess4j 5.19.0` jar:

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
computed in `OcrService.tryComputeConfidence` by averaging per-word
confidences from `tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD)`.
This is wrapped in its own try/catch returning `null` on failure, separate
from the main `doOCR` call, since text extraction is the critical path and
confidence is best-effort (`ocrConfidence` is nullable in the API contract).

### Pagination

`GET /api/captures` uses Spring Data Web's `Pageable`/`@PageableDefault`
binding (`page`, `size`, `sort` query params) and returns a raw
`Page<Capture>` — the full Spring Data page envelope (`content`,
`totalElements`, `totalPages`, etc.), not a custom DTO. The client's
`loadHistory()` in `index.html` reads `page.content`.

## Reference docs in this repo

- `README.md` — setup/run instructions and full API reference (request/response shapes, error status codes).
- `CONCEPTS.md` — line-by-line educational explanation of every Java/Maven/Spring/MongoDB/Tess4J concept used in this codebase, written for someone learning the stack. Read it if unsure why something is structured a particular way.
