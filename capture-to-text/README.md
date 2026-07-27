# Capture to Text — Phase 1 (MVP)

Upload or drag-drop an image, extract its text via OCR (Tesseract, through the
Tess4J Java wrapper), and copy the result. Every capture is saved to MongoDB
so you can browse history.

**Architecture (Phase 1):** `Browser (static HTML/JS) → Spring Boot REST API → Tess4J (OCR) → MongoDB`

No queue, no worker service, no auth, no Docker in this phase — that's Phase 2+.

---

## Prerequisites

| Tool | Version used in this project | Required? |
|---|---|---|
| JDK | 17+ (built/tested with Temurin 21) | Yes |
| MongoDB Community Server | tested with 8.3.4, default port 27017 | Yes, running locally |
| Maven | **not required** — the bundled Maven Wrapper (`mvnw` / `mvnw.cmd`) downloads Maven 3.9.9 automatically on first run | No |
| Tesseract OCR | **not required as a separate install on Windows/Linux/macOS** — see note below | No |

### About Tesseract / Tess4J

This project uses [Tess4J](https://github.com/nguyenq/tess4j), a JNA wrapper
around the native Tesseract + Leptonica libraries. Tess4J's Maven artifact
**bundles the native binaries and the English (`eng`) language data** for
Windows, Linux, and macOS, and `OcrService` extracts them to a temp directory
at startup (`LoadLibs.extractTessResources("tessdata")`). You do **not** need
to install Tesseract separately or download `eng.traineddata` yourself.

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

## 2. Run the application

From the project root (`capture-to-text/`):

**Windows:**
```powershell
$env:JAVA_HOME = "C:\Path\To\Your\JDK"   # only if JAVA_HOME isn't already set
.\mvnw.cmd spring-boot:run
```

**macOS/Linux:**
```bash
./mvnw spring-boot:run
```

First run downloads Maven itself (via the wrapper) plus all dependencies —
give it a minute. Once you see:
```
Started CaptureToTextApplication in X.XXX seconds
```
open **http://localhost:8080** in a browser.

To just compile/package without running:
```powershell
.\mvnw.cmd clean package
java -jar target\capture-to-text-0.1.0.jar
```

---

## 3. Using the client

- Drag an image onto the drop zone, click it to open a file picker, or
  paste (Ctrl/Cmd+V) a screenshot directly from your clipboard.
- The extracted text appears in the textarea with the OCR confidence score;
  click **Copy to clipboard** to grab it.
- Past captures appear in the **History** list (newest first); click one to
  reload its text into the textarea.

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/captures` | Multipart upload, field name `image`. Runs OCR synchronously and saves the result. Returns the saved `Capture` (201). |
| `GET` | `/api/captures` | Paginated list, newest first. Supports standard Spring pagination params: `?page=0&size=20&sort=createdAt,desc`. |
| `GET` | `/api/captures/{id}` | Fetch a single capture by id (404 if not found). |

Error responses are JSON with `timestamp`, `status`, `error`, `message` —
non-image uploads return `400`, OCR failures return `422`, missing ids
return `404`, oversized uploads return `413`.

Example:
```bash
curl -X POST http://localhost:8080/api/captures -F "image=@screenshot.png"
```

---

## Project layout

```
capture-to-text/
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper (no local Maven install needed)
├── pom.xml
├── src/main/java/com/capturetotext/app/
│   ├── CaptureToTextApplication.java
│   ├── controller/CaptureController.java
│   ├── service/CaptureService.java   # orchestrates validation + OCR + persistence
│   ├── service/OcrService.java       # Tess4J integration
│   ├── service/OcrResult.java
│   ├── repository/CaptureRepository.java
│   ├── model/Capture.java            # MongoDB @Document
│   └── exception/                    # custom exceptions + @RestControllerAdvice
├── src/main/resources/
│   ├── application.properties
│   └── static/index.html             # the entire client (vanilla HTML/JS)
└── CONCEPTS.md                       # deep-dive explanations of every concept used here
```

## What's next (Phase 2, not built here)

- Move OCR off the request thread into an async worker (Kafka).
- Separate worker service / horizontal scaling.
- Browser extension for direct screen-region capture.
- Auth / user accounts.
