# KNOWLEDGE.md — 2026-08-31

Session notes on servlet container threading, the JVM/process/native-code
boundary, and Kafka partition/consumer-group mechanics — grounded in this
repo's actual code (`CaptureController`, `CaptureService`, `OcrService`,
`CaptureUploadListener`) rather than abstract theory. Companion to
`Learning/lessons/0006-kafka-fundamentals.html` (the "lesson 6" this session
picked back up from) — that lesson covers topic/partition/consumer-group
definitions; this doc goes one level deeper into *why* those mechanics work
the way they do, plus a live experiment that measured it.

## 1. Servlet container threading (Tomcat)

Spring Boot embeds Tomcat. Neither module overrides its thread pool, so both
run on defaults: `server.tomcat.threads.max=200`, `threads.min-spare=10`.

Tomcat's NIO connector splits work into roles:
- **Acceptor thread(s)** — accept incoming TCP connections.
- **Poller thread(s)** — use OS-level `epoll`/`select` to watch many sockets
  at once for "this one has a full request ready," which is what lets
  Tomcat hold thousands of idle keep-alive connections without a thread per
  connection.
- **Worker thread pool** (the 200) — only checked out once a request is
  actually ready to process. One worker thread runs the entire request —
  filters, `DispatcherServlet`, controller, service layer — and is only
  returned to the pool when the response is fully written.

This is a **blocking, thread-per-request model**: if your code blocks (a
Mongo call, a MinIO upload, previously — Phase 1 — a synchronous Tesseract
call), the worker thread just sits there holding its pool slot for the
duration. Enough concurrent slow requests exhausts the 200-thread pool and
every other request queues behind them, regardless of idle CPU cores. This
was the literal motivation for Phase 2's Kafka-based async pipeline.

**Comparison to a real-time C++ multithreaded framework** (e.g. a robotics
control system): the two threading models optimize for different things.

| | Tomcat / servlet container | RT robotics C++ framework |
|---|---|---|
| Thread assignment | Generic pool, any thread handles any request | Fixed, dedicated threads per role (control loop, sensor IO) |
| Scheduling goal | Throughput/fairness across many clients | Deterministic timing, bounded jitter |
| Blocking | Expected, fine within pool capacity | Often forbidden in the hot path — risks missing a deadline |
| Priority | None — all worker threads equal | Explicit OS priority (`SCHED_FIFO`/`SCHED_RR`), priority-inversion is a real concern |
| Overload failure mode | Threads exhausted → requests queue/reject | Missed deadline → potentially unsafe behavior |

## 2. Process, JVM, and threads — the memory-sharing hierarchy

- A **process** is the OS's isolation unit: its own virtual address space,
  invisible to other processes by default. A running JVM *is* one OS
  process — `.\mvnw.cmd spring-boot:run` for the worker spawns exactly one
  `java.exe` process.
- Inside that one process, the JVM interprets/JIT-compiles bytecode and
  manages a garbage-collected heap. A Java `Thread` on modern HotSpot maps
  directly to a real OS thread — not a simulated "green thread" — the OS
  scheduler treats it exactly like a `std::thread`.
- **Every thread inside one JVM process shares that process's single
  address space** — same heap, same static fields, same singleton bean
  instances. This is why `OcrService`'s one `ITesseract` field, as a
  Spring `@Service` singleton, would be the *same object* seen by all
  threads in that process — whereas separate worker *processes* each get
  their own JVM, own heap, own independent `OcrService` instance, with zero
  sharing possible.

## 3. JNA and the native-memory boundary — why shared Tesseract access was unsafe

Pure Java objects get real safety guarantees from the JVM: no dangling
pointers, no out-of-bounds writes, no use-after-free. Tesseract's actual OCR
engine, though, is native C/C++ code (bundled by Tess4J, extracted at
runtime via `LoadLibs.extractTessResources("tessdata")`). Java can't call a
native `.dll`/`.so` directly — **JNA (Java Native Access)** is the library
that lets Java call into a native shared library at runtime without
hand-written JNI glue.

When `new Tesseract()` runs, JNA calls the native engine's init function,
which allocates a block of memory *outside* the JVM's managed heap, holding
the engine's internal state (loaded language data, current image buffer,
progress). JNA hands Java a lightweight wrapper object — a **handle** —
around a pointer into that native memory. Every `doOCR()`/`getWords()` call
goes back through JNA into that same memory and mutates it.

**The JVM's safety guarantees stop at that boundary.** On the native side of
a JNA call there's no garbage collector, no bounds checking, no atomicity
guarantee — just whatever the native library itself does about concurrent
access (Tesseract generally doesn't tolerate it). Two threads calling
`doOCR()` on the same handle simultaneously both mutate the same native
memory region with zero JVM-level arbitration — the failure mode is native
corruption or a crash, not a catchable Java exception, because the JVM
cannot see into that memory at all.

This is the same class of bug as sharing a raw C++ pointer across threads
with no mutex — undefined behavior, no safety net. JNA doesn't introduce a
new kind of danger; it reopens a hazard Java normally shields you from, for
the specific slice of memory a native library owns.

## 4. Guarding a shared instance vs. eliminating the sharing

A subtlety worth being explicit about, since it changes the fix: there are
two different ways to make concurrent access to shared state safe, and they
have very different implications for whether you actually get parallelism.

- **(a) Guard the sharing** — one shared instance, lock around access to
  it. Correct when the critical section is *small* relative to the total
  work (e.g. a shared counter touched for a microsecond). Threads spend
  most of their time doing unguarded, genuinely parallel work and only
  briefly queue for the shared bit.
- **(b) Eliminate the sharing** — give each thread its own private
  instance, so there's nothing left to guard.

Wrapping the *entire* `doOCR()` call in a lock around one shared `Tesseract`
instance would be (a) misapplied to a case that needed (b) — the "critical
section" would be 100% of the work, so 3 threads would serialize back down
to the throughput of 1, while still paying full lock-contention and
context-switch overhead. The actual fix applied here is (b): a
`ThreadLocal<ITesseract>` gives each consumer thread its own private native
handle, so there is nothing shared and nothing to lock — threads run
`doOCR()` on genuinely separate native memory at the same instant, on
separate cores.

## 5. Kafka offset and "consumer position" — concrete mechanics

Per Confluent's consumer-design docs (quoted in lesson 6): *"the only
metadata retained on a per-consumer basis is the offset — the position of
that consumer in a topic."* Concretely:

- Each partition is physically an **append-only log** — sequential files on
  disk on the broker, split into segments. Every message is appended at the
  end and assigned the next integer in sequence (0, 1, 2, ...), scoped
  per-partition. This numbering never goes backward or gets reused, even
  though old segments eventually get deleted by retention policy.
- **Offset** = an index into that log. Closer to "an index into a
  file that only ever gets appended to" than an in-memory array — it's
  on-disk, persistent across restarts, and per-partition rather than one
  global sequence.
- **Position** = how far a given *consumer group* has read into a given
  partition — the next offset it will fetch. Stored as an integer keyed by
  `(consumer group id, topic, partition)`, persisted by Kafka itself in an
  internal compacted topic, `__consumer_offsets`.
- **Position is not "which consumer number in the group."** That's a
  separate concept — **partition assignment**: which consumer *instance*
  (or, more precisely, which consumer *thread*) currently owns a given
  partition. Assignment can change over time (a *rebalance*, e.g. when
  group membership changes); the offset counter itself is indifferent to
  which instance is doing the reading.

This is exactly the mechanism a prior session's crash/restart test
exercised: when a worker died mid-`handleUpload` without committing, the
*position* for that partition stayed at the old value; when a replacement
joined the same group, it was reassigned that partition and resumed from
the stale committed position, redelivering the in-flight message. Consumers
control replay, not the broker.

## 6. The real unit of Kafka parallelism is a consumer *thread*, not a process or an instance

With 3 partitions and 1 consumer thread, that thread is assigned all 3
partitions — `poll()` fetches from all of them in one batch per round-trip,
interleaved, but **only one message is processed at a time**, since there's
only one thread. Partition count is invisible to throughput in this case:
4 messages spread across 3 partitions still take `4 × (time per job)`
wall-clock, identical to if they'd all landed in one partition.

Real parallelism requires multiple consumer *threads* within the group —
achieved either by:
1. **Multiple worker processes**, each an independent JVM contributing one
   consumer thread to the group (this is what the topic's `KAFKA_NUM_PARTITIONS=3`
   in `docker-compose.yml` was set up to demonstrate), or
2. **`concurrency` on a single `@KafkaListener`**, which spins up N internal
   consumer threads inside *one* JVM, each claiming a partition.


- So the idea is each partition can be owned by multiple consumer groups, consisting of multiple kafka consumer threads (which is its own threads within the JVM, under Kafka ), and at any given moment a partition can be consumed by only one thread from each consumer group, and processsing through the partition sequentially.

-  Consumer groups are used because we want to create independent consumers for the **same event stream**, e.g. the billing and email services both requires the same order information for their own further processing

- Note that each consumer group can consist of **independent working process**, each with multiple kafka threads and each of those threads can be assigned to different partitions OR each process having a single kafka thread. e.g. :

        Consumer Group A
      │
      └── Process / JVM 1
          │
          ├── Consumer Thread 1 → Partition 0
          ├── Consumer Thread 2 → Partition 1
          └── Consumer Thread 3 → Partition 2

      Consumer Group A
      │
      ├── Process 1
      │   └── Consumer Thread 1 → Partition 0
      │
      ├── Process 2
      │   └── Consumer Thread 2 → Partition 1
      │
      └── Process 3
          └── Consumer Thread 3 → Partition 2

For pure CPU-bound throughput on one machine, these two options have the
**same ceiling** — the OS scheduler puts runnable threads on cores
regardless of which process they belong to. What actually differs between
them:

| Factor | N processes | `concurrency=N` in 1 process |
|---|---|---|
| Fault isolation | A native crash kills 1 partition's worker; others keep running | A native crash kills the whole JVM — all N partitions at once |
| Scaling across machines | Yes — orchestrators (k8s `replicas`) scale at the process/pod level | No — capped by one machine's cores |
| Per-unit overhead | ~Nx baseline (heap, Mongo pool, MinIO client each) | Shared baseline, cheaper per unit of concurrency |
| Shared-state safety | Free — separate address spaces | Must actually audit/fix (e.g. `ThreadLocal`, as done here) |
| Operational visibility | Per-instance metrics/restarts "for free" from infra | Requires your own thread-level instrumentation |

Real production systems typically combine both as two independent dials:
partition count set higher than the pod count you intend to run, then some
number of pods each with `concurrency = partitions / pods`, balancing
overhead against blast radius. Given `OcrService` wraps a not-thread-safe
native library, the fault-isolation column favors separate processes (or
concurrency only after the `ThreadLocal` fix below) for this specific
workload.

## 7. Applied fix + live experiment

**Changes made** (`worker/src/main/java/com/capturetotext/worker/...`):
- `service/OcrService.java` — replaced the shared `ITesseract tesseract`
  field with `ThreadLocal<ITesseract> tesseractThreadLocal`, lazily
  constructing one native engine instance per thread on first use.
- `listener/CaptureUploadListener.java` — added `concurrency = "3"` to
  `@KafkaListener`, plus `START`/`FINISH` log lines around the OCR work.

**Experiment:** with the worker running 3 consumer threads
(`consumer-ocr-workers-1/2/3`, one per partition), 12 uploads through the
normal API were fired in two bursts (6 truly concurrent, 6 spaced 0.4s
apart) — **all 12 landed on partition 0 regardless**. Kafka's default
producer partitioner (adaptive "sticky" partitioning, no message key set on
`kafkaTemplate.send(topic, id)`) never rotated off partition 0 under this
low, steady load, so threads 2 and 3 sat idle despite being correctly
provisioned. This is a real operational lesson, not a demo artifact:
**"designed to parallelize" and "actually parallelizing" are different
claims** — the gap is partition *distribution*, not partition *count* or
thread count. A load test that only checks consumer thread count can't tell
an idle thread from a nonexistent one; check per-partition message counts
(`kafka-consumer-groups.sh --describe`) too.

To force a genuine test, 3 already-processed capture IDs were republished
directly to explicit partitions 0/1/2 via a one-off Python script
(`kafka-python`), bypassing the app's producer. Result, from the worker log:

```
01:35:47.475  START  #0-1-C-1   capture ...10f   (partition 1)
01:35:47.478  START  #0-0-C-1   capture ...10e   (partition 0)
01:35:47.478  START  #0-2-C-1   capture ...110   (partition 2)
                ↑ all three START within a 3ms window, before any FINISH
01:35:47.602  FINISH #0-0-C-1   (127ms of work)
01:35:47.616  FINISH #0-1-C-1   (141ms of work)
01:35:47.617  FINISH #0-2-C-1   (139ms of work)
```

All three `START`s landed within 3ms of each other, on three different
threads, before any had finished — genuine concurrent execution, not
interleaving. Total wall time (first START to last FINISH) was ~142ms;
sequential execution of three ~130ms jobs would have taken ~400ms. All
three results came back correct and identical
(`"Hello Kafka OCR\n"`, confidence `96.7013448079427`) — confirming the
`ThreadLocal` fix gave each thread a genuinely independent native instance,
with no cross-thread corruption despite simultaneous native calls.

**Status as of this writing:** both changes are live in the working tree,
uncommitted. Whether to keep `concurrency="3"` + the `ThreadLocal` fix as
the new baseline (vs. reverting to the original single-threaded worker) was
still an open decision at the point this document was written.
