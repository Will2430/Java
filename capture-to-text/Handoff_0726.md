# Session Handoff — Capture-to-Text Phase 1 OCR MVP (Spring Boot + Tess4J + MongoDB)

## Where it started
User asked to build Phase 1 of a "capture-to-text" OCR tool: Spring Boot 3.x REST API + Tess4J OCR + MongoDB persistence + a single vanilla HTML/JS client, scaffolded in `C:\Users\willi\Downloads\L\Java`. Explicit non-goals: no Kafka/worker/Postgres/Docker/auth/browser-extension (that's Phase 2). User also wanted a detailed single-markdown explainer covering Java/Spring/Maven/Mongo concepts, and said to ask clarifying questions first.

## Decisions locked + what shipped
- Clarified with user up front: MongoDB installed fresh via `winget` (not pre-existing), and Maven Wrapper used instead of a global Maven install.
- Full project scaffolded at `C:\Users\willi\Downloads\L\Java\capture-to-text` — standard Maven layout, package `com.capturetotext.app`, Spring Boot `3.5.16`, Tess4J `5.19.0` (versions confirmed live against Maven Central, not guessed).
- Maven Wrapper set up by downloading the real `mvnw`/`mvnw.cmd` scripts from `apache/maven-wrapper` GitHub and hand-writing `.mvn/wrapper/maven-wrapper.properties` (Maven 3.9.9, wrapper 3.3.2) — verified working end-to-end.
- Backend layers implemented: `model/Capture.java`, `repository/CaptureRepository.java`, `service/OcrService.java` (Tess4J wrapper), `service/OcrResult.java` (record), `service/CaptureService.java`, `controller/CaptureController.java`, `exception/{InvalidImageException,OcrProcessingException,CaptureNotFoundException,GlobalExceptionHandler}.java`.
- Found and fixed two real Tess4J API discrepancies vs. the library's own wiki docs (discovered via a standalone reproduction, not guessed): `LoadLibs` lives in `net.sourceforge.tess4j.util.LoadLibs` (not the base package), and `setDatapath()` must point directly at the extracted `tessdata` folder (`.getAbsolutePath()`), not its parent (`.getParent()`) — the wiki sample is wrong for `5.19.0`. Both fixes are in `OcrService.java` and documented in `CONCEPTS.md` §10.
- Found and fixed a real bug during browser testing: a missing `favicon.ico` was falling into the catch-all `Exception` handler and returning `500` instead of `404`. Added an explicit `@ExceptionHandler(NoResourceFoundException.class)` in `GlobalExceptionHandler.java`.
- Client page `src/main/resources/static/index.html` — drag/drop, file picker, clipboard paste, copy-to-clipboard, history list — served same-origin (no CORS needed).
- `README.md` and `CONCEPTS.md` written at project root; `.gitignore` added (`target/`, `*.log`, `app.pid.txt`, IDE dirs).
- End-to-end verified: MongoDB installed+running as Windows service, app compiled and run via `mvnw.cmd spring-boot:run`, tested via curl (POST/GET/GET-by-id/404/400 all correct) and via a real Playwright browser session (upload → OCR text "Hello Capture To Text 123" at 96.5% confidence → saved → appears in history → copy button works).

## Key files for next session
- `C:\Users\willi\Downloads\L\Java\capture-to-text\CONCEPTS.md` — read first if continuing docs/teaching; §10 has the two Tess4J gotchas that will resurface if `tess4j.version` is ever bumped.
- `C:\Users\willi\Downloads\L\Java\capture-to-text\README.md` — setup/run/API reference.
- `C:\Users\willi\Downloads\L\Java\capture-to-text\pom.xml` — dependency versions (Spring Boot 3.5.16, Tess4J 5.19.0).
- `C:\Users\willi\Downloads\L\Java\capture-to-text\src\main\java\com\capturetotext\app\service\OcrService.java` — the datapath fix lives here; don't revert to `.getParent()`.
- `C:\Users\willi\Downloads\L\Java\capture-to-text\src\main\java\com\capturetotext\app\exception\GlobalExceptionHandler.java` — favicon/404 fix lives here.
- No plan file was used this session (no `EnterPlanMode`/plan-mode file involved). No memory files were written this session.

## Running state
- **Spring Boot app**: running as a detached Windows process (PID ~22208 at last check, launched via PowerShell `Start-Process -FilePath .\mvnw.cmd -ArgumentList "-q","spring-boot:run" -RedirectStandardOutput app.log -RedirectStandardError app.err.log`), **not tracked by a harness task ID** — it was started with `Start-Process`, so it survives independently of this conversation. Serving at `http://localhost:8080`. To stop: `Get-Process -Name java | Stop-Process -Force` (kills any/all java processes on the machine — fine here since nothing else Java is expected to be running).
- **MongoDB**: running as Windows service `MongoDB` (installed via winget, MongoDB Server 8.3.4), listening on default port 27017. To stop: `Stop-Service MongoDB`. To restart: `Start-Service MongoDB`.
- No other background shells, no worktrees, no branches (directory is not a git repo — `git init` never run).
- Log files `app.log` / `app.err.log` in the project root are currently locked/in-use by the running process (couldn't delete them mid-session); safe to delete once the app is stopped. They're in `.gitignore`.

## Verification — how to confirm things still work
- `curl http://localhost:8080/` — expect `200`, serves `index.html`.
- `curl http://localhost:8080/api/captures` — expect `200` with a `Page<Capture>` JSON body; should already contain 2 test captures from this session ("Hello Capture To Text 123").
- `curl -X POST http://localhost:8080/api/captures -F "image=@<some-image.png>"` — expect `201` with `extractedText` and `ocrConfidence` populated.
- If the app isn't running: from `C:\Users\willi\Downloads\L\Java\capture-to-text`, set `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"` then `.\mvnw.cmd spring-boot:run`.

## Deferred + open questions
- Deferred: everything explicitly scoped to Phase 2 by the user (Kafka async worker, separate worker service, browser extension, auth) — user said they'll return with a Phase 2 prompt.
- Open: none outstanding — both clarifying questions asked this session (MongoDB handling, Maven setup) were answered by the user and acted on. No unresolved questions pending.

## Pick up here
Wait for the user's Phase 2 prompt (splitting OCR into an async Kafka-based worker); no in-progress work to resume on Phase 1.
