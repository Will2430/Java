# Concepts Reference — Capture to Text (Phase 1)

This file explains **every concept, tool, and syntax pattern** used in this
project, in the order you'd naturally run into them. It's written for
someone comfortable with basic programming but new to Java/Spring/Maven/
MongoDB specifically. Each section ties back to real files in this repo.

---

## Table of contents

1. [How the pieces fit together](#1-how-the-pieces-fit-together)
2. [Maven: the build tool](#2-maven-the-build-tool)
3. [Java language features used here](#3-java-language-features-used-here)
4. [Spring Boot fundamentals](#4-spring-boot-fundamentals)
5. [Spring MVC — building the REST API](#5-spring-mvc--building-the-rest-api)
6. [Spring Data MongoDB](#6-spring-data-mongodb)
7. [Tess4J and OCR](#7-tess4j-and-ocr)
8. [Error handling design](#8-error-handling-design)
9. [The client page (vanilla JS)](#9-the-client-page-vanilla-js)
10. [Gotchas hit while building this (and how they were found)](#10-gotchas-hit-while-building-this)

---

## 1. How the pieces fit together

```
Browser (index.html)
   │  fetch('/api/captures', { method: 'POST', body: FormData })
   ▼
CaptureController          ← Spring MVC: turns HTTP requests into Java method calls
   │  calls
   ▼
CaptureService             ← validation + orchestration (no HTTP or DB knowledge)
   │  calls
   ├──▶ OcrService          ← wraps Tess4J, turns an image File into text + confidence
   └──▶ CaptureRepository   ← Spring Data interface, talks to MongoDB
```

This is a classic **layered architecture**:
- **Controller** — knows about HTTP (status codes, request params), nothing about OCR or Mongo internals.
- **Service** — knows business rules ("reject non-images", "wrap OCR errors"), nothing about HTTP.
- **Repository** — knows how to persist one type of object, nothing about business rules.

Each layer only calls the layer directly below it. This is why `CaptureController`
never imports anything from `net.sourceforge.tess4j` — it doesn't need to
know OCR exists at all, only that `CaptureService` returns a `Capture`.

---

## 2. Maven: the build tool

Maven answers three questions for a Java project: **what dependencies does
this need, how do I compile/test/package it, and where do I get the tools to
do that** — all declared in one XML file, `pom.xml` ("Project Object Model").

### Coordinates

Every artifact in the Maven universe (including your own project) is
identified by three strings:

```xml
<groupId>com.capturetotext</groupId>   <!-- who -->
<artifactId>capture-to-text</artifactId> <!-- what -->
<version>0.1.0</version>                 <!-- which release -->
```

When you write `<dependency><groupId>net.sourceforge.tess4j</groupId>...`,
Maven looks up exactly that groupId/artifactId/version combination in Maven
Central (the public repository) or your local cache (`~/.m2/repository`),
downloads the `.jar`, and puts it on the compiler's classpath.

### The parent POM and dependency management

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
</parent>
```

This isn't just "inherit some settings" — `spring-boot-starter-parent` is a
**Bill of Materials (BOM)**. It pre-selects compatible versions for
hundreds of libraries (Jackson, SLF4J, the Mongo driver, JUnit, etc.). That's
why almost none of the `<dependency>` blocks in `pom.xml` specify a
`<version>` — Maven fills it in from the parent's dependency management,
guaranteeing that (for example) Spring Web's Jackson version and Spring Data
Mongo's Jackson version can't silently drift apart and break JSON
serialization. The one dependency *with* an explicit version, `tess4j`, is
version-pinned manually because Spring doesn't know about it.

### Starters

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

A "starter" is an empty jar whose only purpose is to pull in a curated set
of *transitive* dependencies. `spring-boot-starter-web` alone brings in
Spring MVC, an embedded Tomcat server, and Jackson — you never list those
individually. `spring-boot-starter-data-mongodb` similarly brings in the
MongoDB Java driver and Spring Data's repository machinery.

### The Maven lifecycle

Maven defines a fixed sequence of **phases**; running a phase runs every
phase before it too:

```
validate → compile → test → package → verify → install → deploy
```

- `mvnw compile` — just compiles `src/main/java` into `target/classes`.
- `mvnw package` — compiles, runs tests, then bundles everything into
  `target/capture-to-text-0.1.0.jar` (an executable "fat jar" containing
  your code *and* every dependency, thanks to `spring-boot-maven-plugin`
  in the `<build><plugins>` section of `pom.xml`).
- `mvnw spring-boot:run` — a goal from the Spring Boot plugin that compiles
  and runs the app directly, without producing a jar. This is what you use
  during development.

### The Maven Wrapper (`mvnw` / `mvnw.cmd`)

Notice the project has no dependency on Maven being installed on your
machine. `mvnw.cmd` (Windows) / `mvnw` (Unix) are checked-in scripts that:

1. Read `.mvn/wrapper/maven-wrapper.properties` to find which exact Maven
   version the project wants (`3.9.9` here).
2. Download that Maven distribution into `~/.m2/wrapper/dists/` if it isn't
   already cached — completely separate from any Maven you may or may not
   have installed globally.
3. Invoke that Maven install with whatever arguments you passed to `mvnw`.

This guarantees everyone who clones the repo builds with the *exact* same
Maven version, and removes "works on my machine" build-tool drift as a
variable entirely.

---

## 3. Java language features used here

### Classes vs. records

Most types in this project are ordinary classes with private fields and
explicit getters/setters — e.g. `Capture`:

```java
@Document(collection = "captures")
public class Capture {
    @Id
    private String id;
    private String extractedText;
    ...
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
```

But `OcrResult` is a **record** (Java 16+):

```java
public record OcrResult(String text, Double confidence) {}
```

One line generates: a constructor `OcrResult(String, Double)`, accessor
methods `text()` and `confidence()` (note: no `get` prefix), plus working
`equals()`, `hashCode()`, and `toString()`. Records are immutable — every
field is `final` and set once in the constructor.

**Why `Capture` isn't a record:** Spring Data needs to assign the
Mongo-generated `id` *after* you've constructed the object (it starts as
`null`, and MongoDB fills it in on insert). Records can express this via a
"wither" pattern, but a plain mutable class with a `setId()` is simpler here
and is what the vast majority of Spring Data tutorials use. `OcrResult`
never has this problem — it's fully formed the moment OCR finishes, so a
record is the more idiomatic modern-Java choice.

### Interfaces implemented at runtime — `CaptureRepository`

```java
public interface CaptureRepository extends MongoRepository<Capture, String> {
}
```

This has **zero method bodies**, yet `captureRepository.save(...)`,
`.findById(...)`, `.findAll(pageable)` all work at runtime. Spring Data
generates a dynamic proxy class implementing this interface when the
application starts, by inspecting `MongoRepository<Capture, String>`
(`Capture` = the entity type, `String` = the type of its `@Id` field). If we
later added `List<Capture> findBySourceFilename(String name);`, Spring Data
would parse that method *name* and build the equivalent MongoDB query
automatically — no SQL/query string required for simple lookups.

### Checked vs. unchecked exceptions

`TesseractException` (thrown by Tess4J) is a **checked** exception — Java
forces you to either catch it or declare `throws TesseractException` on
your method, which is why `OcrService.extractText` declares
`throws TesseractException`. `CaptureService` catches it and re-throws it as
`OcrProcessingException`, which extends `RuntimeException` (**unchecked**).
This is a deliberate boundary: the low-level "how OCR can fail" detail stays
checked near the OCR code, but once translated into an application-level
error, it becomes an unchecked exception so it can propagate up through
`CaptureController` without every layer needing a `throws` clause.

### Streams and lambdas — computing average confidence

```java
return words.stream()
        .mapToDouble(Word::getConfidence)
        .average()
        .orElse(0.0);
```

`words` is a `List<Word>`. `.stream()` turns it into a pipeline;
`.mapToDouble(Word::getConfidence)` is a **method reference** (shorthand for
`word -> word.getConfidence()`) that projects each `Word` down to its
`float` confidence, producing a `DoubleStream`; `.average()` returns an
`OptionalDouble` (empty if the stream was empty); `.orElse(0.0)` unwraps it
with a fallback. This whole pipeline replaces what would otherwise be a
manual loop with a running sum and counter.

### `Optional<T>` — expressing "might not exist"

```java
public Optional<Capture> getCapture(String id) {
    return captureRepository.findById(id);
}
```
```java
public Capture getCapture(@PathVariable String id) {
    return captureService.getCapture(id)
            .orElseThrow(() -> new CaptureNotFoundException(id));
}
```

`findById` can't return `null` for "not found" without every caller
remembering to null-check — `Optional` makes the possibility of absence part
of the method's type signature. `.orElseThrow(...)` either unwraps the value
or throws the supplied exception, which `GlobalExceptionHandler` then turns
into a 404.

### `try / finally` for cleanup

```java
File tempFile = null;
try {
    tempFile = File.createTempFile("capture-", suffixFor(file.getOriginalFilename()));
    ...
} finally {
    if (tempFile != null) {
        tempFile.delete();
    }
}
```

Uploaded bytes arrive as an in-memory/temp-backed `MultipartFile`, but
Tess4J's API wants a `java.io.File` on disk, so `CaptureService` writes it to
its own temp file first. `finally` guarantees that temp file is deleted
whether OCR succeeds, throws, or anything else happens — it always runs.

---

## 4. Spring Boot fundamentals

### Inversion of Control (IoC) and Dependency Injection (DI)

In plain Java, `CaptureController` would have to do
`new CaptureService(new OcrService(), new CaptureRepository())` itself —
and `CaptureRepository` isn't even something you *can* `new` up, since it's
an interface with no implementation in your code. Spring inverts this: at
startup it scans your classes, builds every object it needs to ("beans"),
wires their dependencies together, and hands your controller an
already-assembled `CaptureService`. You never call `new` on any of these
types yourself.

```java
@Service
public class CaptureService {
    private final OcrService ocrService;
    private final CaptureRepository captureRepository;

    public CaptureService(OcrService ocrService, CaptureRepository captureRepository) {
        this.ocrService = ocrService;
        this.captureRepository = captureRepository;
    }
    ...
}
```

This is **constructor injection** — no `@Autowired` annotation is needed
because Spring auto-wires the sole constructor of any Spring-managed class.
It's preferred over field injection (`@Autowired private OcrService ocrService;`)
because: dependencies are explicit and final (can't be reassigned or left
uninitialized), and the class can be constructed and unit-tested with plain
`new CaptureService(fakeOcr, fakeRepo)` — no Spring context needed in tests.

### Stereotype annotations

| Annotation | Marks a class as... |
|---|---|
| `@SpringBootApplication` | The entry point — combines `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` |
| `@RestController` | An MVC controller whose method return values are serialized straight to the HTTP response body (as opposed to `@Controller`, which expects view names) |
| `@Service` | A business-logic bean (semantically distinct from `@Component`, purely for readability/organization) |
| `@RestControllerAdvice` | A global handler that intercepts exceptions from every `@RestController` |

All of these are specializations of the generic `@Component` — Spring's
component scan (triggered by `@SpringBootApplication` on
`CaptureToTextApplication`, which by default scans its own package and all
sub-packages) picks up any class annotated with any of them and registers it
as a bean.

### Auto-configuration

`@SpringBootApplication` also triggers **auto-configuration**: Spring Boot
inspects what's on your classpath and configures sensible defaults
accordingly. Because `spring-boot-starter-data-mongodb` is a dependency,
Spring Boot auto-configures a `MongoClient` and `MongoTemplate` bean pointed
at whatever `spring.data.mongodb.uri` is set to in
`application.properties` — you never write a single line of Mongo
connection/client-building code.

### `application.properties`

```properties
spring.data.mongodb.uri=mongodb://localhost:27017/capturetotext
spring.servlet.multipart.max-file-size=10MB
```

This is Spring Boot's externalized configuration mechanism — properties
here override the framework's defaults, and every "starter" defines its own
set of recognized keys. Changing `spring.data.mongodb.uri` to point at a
different host/database requires no code change at all, only a config
change (which is exactly what you'd override with an environment variable
or a different `application-prod.properties` profile in a real deployment).

### Embedded Tomcat

`spring-boot-starter-web` bundles an embedded Apache Tomcat server. There's
no separate app-server install/config — `SpringApplication.run(...)` in
`CaptureToTextApplication.main()` starts an in-process HTTP server on
`server.port` (8080 here) as part of booting the application context.

---

## 5. Spring MVC — building the REST API

### Mapping HTTP requests to methods

```java
@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public Capture createCapture(@RequestParam("image") MultipartFile image) { ... }

    @GetMapping
    public Page<Capture> listCaptures(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) { ... }

    @GetMapping("/{id}")
    public Capture getCapture(@PathVariable String id) { ... }
}
```

- `@RequestMapping("/api/captures")` at the class level prefixes every
  method's path.
- `@PostMapping` / `@GetMapping` are shorthand for
  `@RequestMapping(method = POST/GET)`.
- `@RequestParam("image")` extracts the multipart part named `"image"` —
  this name must match exactly what the client sends
  (`formData.append('image', file)` in `index.html`).
- `@PathVariable String id` binds the `{id}` segment of the URL.
- `MultipartFile` is Spring's abstraction over an uploaded file — regardless
  of whether the underlying servlet container buffers it in memory or spills
  it to disk, you get the same `.getContentType()`, `.getOriginalFilename()`,
  `.transferTo(File)`, `.isEmpty()` API.
- `@ResponseStatus(HttpStatus.CREATED)` sets the HTTP status to `201`
  instead of the MVC default of `200` for a successful POST.

### Returning JSON without writing any serialization code

Every one of these methods just returns a plain Java object
(`Capture`, `Page<Capture>`). Because the class is annotated
`@RestController` (not `@Controller`), Spring wraps the return value with an
`HttpMessageConverter` — specifically, Jackson's converter — which reflects
over the object's getters and produces JSON automatically. This is also why
`Capture` needs conventional getters (`getExtractedText()`, etc.): Jackson's
default behavior is to serialize based on JavaBean-style accessors.

### Pagination — `Pageable` and `Page<T>`

```java
@GetMapping
public Page<Capture> listCaptures(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    return captureService.listCaptures(pageable);
}
```

`Pageable` is populated automatically from query parameters —
`GET /api/captures?page=1&size=10&sort=createdAt,asc` overrides the
`@PageableDefault`. `captureRepository.findAll(pageable)` (inherited free
from `MongoRepository`) returns a `Page<Capture>`, which serializes to JSON
containing `content` (the actual list), plus `totalElements`, `totalPages`,
`number` (current page index), etc. — everything `index.html`'s
`loadHistory()` needs to render a history list without any bespoke
pagination code on either side.

### Multipart size limits

```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

Without this, Spring Boot's defaults are also 10MB per Spring Boot 3.x's own
default — but declaring them explicitly documents the limit and makes it
easy to raise later (e.g. for very large screenshots) without hunting
through framework defaults. Exceeding it throws
`MaxUploadSizeExceededException`, which `GlobalExceptionHandler` catches and
turns into a `413 Payload Too Large` instead of a raw container-level error
page.

---

## 6. Spring Data MongoDB

### Documents vs. rows

MongoDB stores **documents** (JSON-like BSON objects) in **collections**,
not rows in tables. There's no schema migration step — the shape of
`Capture` *is* the schema, and Spring Data creates the `captures` collection
automatically the first time you `save()` something into it.

```java
@Document(collection = "captures")
public class Capture {
    @Id
    private String id;
    ...
}
```

`@Document` tells Spring Data which collection this class maps to.
`@Id` marks the field that corresponds to MongoDB's special `_id` field —
Spring Data handles the translation between Java `id` and Mongo's `_id`
transparently. Leaving `id` as `null` when you build a new `Capture` and
calling `save()` tells MongoDB to generate a fresh `ObjectId` (a 24-character
hex string) for you, which is exactly why `Capture`'s constructor doesn't
take an `id` parameter — it's assigned only after persistence.

### Repository query derivation

`MongoRepository<Capture, String>` already provides, with zero code:
- `save(Capture)` — insert or update (based on whether `id` is set)
- `findById(String)` → `Optional<Capture>`
- `findAll(Pageable)` → `Page<Capture>`
- `deleteById(String)`, `count()`, `existsById(String)`, etc.

If Phase 2 needed e.g. "find all captures from a given source file", you'd
add exactly one line:
```java
List<Capture> findBySourceFilename(String sourceFilename);
```
No implementation — Spring Data parses `findBy` + `SourceFilename` and
builds the equivalent Mongo query (`{ sourceFilename: ? }`) at startup.

### Connection configuration

```properties
spring.data.mongodb.uri=mongodb://localhost:27017/capturetotext
```

One URI captures host, port, and database name. Spring Boot's
auto-configuration parses it and builds the `MongoClient` bean;
`capturetotext` doesn't need to exist beforehand — MongoDB creates
databases and collections lazily on first write.

---

## 7. Tess4J and OCR

### What Tess4J actually is

Tesseract itself is a **C++** OCR engine — it has no native Java API. Tess4J
is a **JNA** (Java Native Access) wrapper: JNA lets Java code call into a
native shared library (`.dll` on Windows, `.so` on Linux) without writing any
JNI glue code by hand. `ITesseract`/`Tesseract` in `net.sourceforge.tess4j`
are thin Java classes whose methods internally call the native
`libtesseract`/`liblept` (Leptonica, an image-processing library Tesseract
depends on) functions.

### Where the native libraries and language data come from

```java
tesseract.setDatapath(LoadLibs.extractTessResources("tessdata").getAbsolutePath());
```

`LoadLibs` (`net.sourceforge.tess4j.util.LoadLibs`) unpacks native
`.dll`/`.so` files *and* `eng.traineddata`/`osd.traineddata`, which are
bundled **inside the tess4j jar itself**, to a temp directory (typically
`%TEMP%\tess4j\` on Windows) the first time it's called. This is why this
project needs no separate Tesseract install — the Windows/Linux/macOS
binaries and English language data ship inside the Maven dependency.
`setDatapath(...)` tells the native Tesseract engine which directory to look
in for `<lang>.traineddata` files.

### OCR in one call

```java
String text = tesseract.doOCR(imageFile);
```

Internally this: loads the image via Leptonica, runs Tesseract's page
segmentation + recognition pipeline using the `eng` language model, and
returns the recognized text as a single `String`.

### Confidence — a second pass over the same image

Tesseract's confidence isn't a single number for the whole page — it's
computed per recognized element. To get *a* number for `ocrConfidence`,
`OcrService` asks for word-level results and averages them:

```java
List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
return words.stream().mapToDouble(Word::getConfidence).average().orElse(0.0);
```

`RIL_WORD` ("Recognition Iterator Level: Word") tells Tesseract's result
iterator to give you one `Word` per recognized word (as opposed to
`RIL_BLOCK`, `RIL_PARA`, `RIL_TEXTLINE`, or `RIL_SYMBOL` for coarser/finer
granularity), each carrying its own `getText()`, `getConfidence()` (0–100),
and `getBoundingBox()`. This whole calculation is wrapped in a try/catch that
returns `null` on any failure — matching the spec's "ocrConfidence (if
available)" — because text extraction is the critical path and confidence
is a nice-to-have.

---

## 8. Error handling design

### Custom exception types

```java
public class InvalidImageException extends RuntimeException { ... }
public class OcrProcessingException extends RuntimeException { ... }
public class CaptureNotFoundException extends RuntimeException { ... }
```

Three distinct exception types for three distinct failure *categories* —
this matters because `GlobalExceptionHandler` maps each type to a different
HTTP status. A single generic `RuntimeException("something went wrong")`
everywhere would force the handler to guess at the right status code from a
string message, which is fragile.

### `@RestControllerAdvice` — a cross-cutting concern

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InvalidImageException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidImage(InvalidImageException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
    ...
}
```

Rather than wrapping every controller method in its own try/catch, this one
class intercepts exceptions thrown *anywhere* in the request-handling
pipeline, matched by exception type — Spring picks the most specific
`@ExceptionHandler` that matches (`InvalidImageException` before the
catch-all `Exception`). This is what satisfies "handle OCR failures
gracefully... don't let it 500 silently": every failure mode returns a
structured JSON body with an appropriate status, and the controller/service
code itself stays free of repetitive error-formatting boilerplate.

Note the specific handler for `NoResourceFoundException` — without it, a
routine 404 for a missing static asset (like a favicon) would fall into the
catch-all `Exception` handler and get misreported as a `500`, which is
exactly the kind of "silent 500" the requirements warn against for a
completely different, non-error situation.

---

## 9. The client page (vanilla JS)

### Why one static HTML file works with zero build step

`index.html` lives in `src/main/resources/static/`. Spring Boot's
`WebMvcAutoConfiguration` serves anything under `static/` on the classpath
directly — `http://localhost:8080/` resolves to `index.html` with no
web server config, no CORS setup, and no separate `npm run build` step.
Because the page and the API share an origin (`localhost:8080`),
`fetch('/api/captures')` just works — no `Access-Control-Allow-Origin`
headers needed. If this were split into a separately-hosted static site
later, that CORS-free ride ends and you'd need `@CrossOrigin` or a
`WebMvcConfigurer` CORS bean.

### Uploading — `FormData` and `fetch`

```js
const formData = new FormData();
formData.append('image', file, file.name || 'pasted-image.png');
const res = await fetch('/api/captures', { method: 'POST', body: formData });
```

`FormData` builds a `multipart/form-data` body identical to what an HTML
`<form>` would submit — the browser sets the `Content-Type` header
(including the multipart boundary) automatically; you must *not* set it
yourself or the boundary will be wrong. The field name `'image'` must match
`@RequestParam("image")` on the server exactly.

### Three ways to get an image in

- **File picker**: clicking the drop zone programmatically clicks a hidden
  `<input type="file">` (`dropZone.addEventListener('click', () => fileInput.click())`).
- **Drag and drop**: `dragover`/`dragenter` call `preventDefault()` (required, or
  the browser's default "open the file" behavior takes over instead of firing
  `drop`); the `drop` handler reads `e.dataTransfer.files[0]`.
- **Clipboard paste**: a `paste` listener on `document` scans
  `e.clipboardData.items` for an `image/*` MIME type and calls
  `item.getAsFile()` — this is what makes pasting a screenshot
  (e.g. from Windows' Snipping Tool) work without ever touching disk.

### Copy to clipboard

```js
await navigator.clipboard.writeText(output.value);
```

The async Clipboard API — requires a secure context (`https://` or
`localhost`, which this satisfies) and, in most browsers, a user gesture
(the button click) to be allowed to run.

---

## 10. Gotchas hit while building this

Worth recording because they're the kind of thing that looks right in
documentation but breaks in practice — this is exactly why the app was
actually run end-to-end rather than just compiled:

1. **`LoadLibs` package.** Tess4J's own wiki code samples show
   `import net.sourceforge.tess4j.*;` and then use `LoadLibs` directly. In
   tess4j `5.19.0`, `LoadLibs` actually lives in
   `net.sourceforge.tess4j.util.LoadLibs` — a separate import is required, or
   the code fails to compile with "cannot find symbol". Found by actually
   compiling against the real jar and inspecting its contents.

2. **`setDatapath(...)` argument.** The wiki sample calls
   `instance.setDatapath(LoadLibs.extractTessResources("tessdata").getParent())`
   — i.e., the *parent* of the extracted `tessdata` folder. Against
   `5.19.0`, that throws `IllegalArgumentException: Specified language data
   does not exist`, because this version's native `Tesseract.init()` looks
   for `<datapath>/eng.traineddata` directly, meaning `setDatapath` must
   point **at** the `tessdata` folder itself
   (`LoadLibs.extractTessResources("tessdata").getAbsolutePath()`), not its
   parent. Found by writing a small standalone reproduction outside Spring
   and trying both paths against the real bundled data until one produced
   actual OCR text instead of an error.

Both are now correctly reflected in `OcrService.java` — but if you upgrade
the `tess4j.version` property in `pom.xml` later, re-verify this behavior;
it clearly isn't stable across versions.
