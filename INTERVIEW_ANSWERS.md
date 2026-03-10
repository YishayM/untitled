# INTERVIEW_ANSWERS.md — Hardest Follow-up Questions

---

## Q1: "The `detected_at` timestamp in the alert is stamped in the worker thread. How far can it drift from actual event occurrence, and how would you fix it?"

### What the code does
`SensitiveDataAlertEngine.processTriple()` stamps `Instant detectedAt = clock.instant()` at line 69 — the first line of the method, before the DB insert. The worker picks up batches from the `LinkedBlockingQueue` via `EventWorker.run()` → `queue.take()` (line 29). There is no timestamp on the `IncomingBatch` record (`IncomingBatch.java`) or on `SensorEvent` (which has `eventTimestamp` but that's a sensor-provided string, not a system ingestion time).

### The issue
Drift has two components:
1. **Queue depth drift**: If the queue has 50k batches backlogged, a batch enqueued now might not be dequeued for minutes. `detected_at` would be minutes after the event arrived at the HTTP layer.
2. **Worker contention drift**: 2×CPU workers sharing HikariCP connections; if DB is slow, workers back up — the batch sits in the queue longer.

The timestamp reflects "when the worker got to it", not "when the sensor reported it" and not even "when we accepted the HTTP request".

### Fix options

**Option A — Stamp at HTTP accept time (best for forensics)**
```java
// IncomingBatch.java — add field
public record IncomingBatch(String accountId, List<SensorEvent> events, Instant receivedAt) { }

// EventIngestionController.java
boolean accepted = ingestionQueue.offer(
    new IncomingBatch(accountId, events, clock.instant()));  // stamp here

// SensitiveDataAlertEngine.processTriple — accept Instant parameter
private void processTriple(TripleKey triple, Instant detectedAt) { ... }
```
**Pro**: Closest to "when we knew about it". **Con**: Requires interface change.

**Option B — Use sensor-provided timestamp (best for event correlation)**
```java
// SensitiveDataAlertEngine.process()
Instant detectedAt = Instant.parse(event.eventTimestamp()); // parse ISO-8601
```
**Pro**: Ties alert to actual sensor observation. **Con**: Trusts external clock; requires format validation; clock skew possible.

**Option C — Keep current approach, document the bound**
Add a queue-depth metric. At 2×CPU workers processing ~1ms/event, max drift = `queue_depth × (1ms / N_WORKERS)`. At 50k backlog and 8 workers, worst case is ~6 seconds. Document this in DESIGN.md as the acceptable SLA gap.

---

## Q2: "Two workers dequeue the same `(accountId, source, destination, classification)` triple concurrently. Walk me through the exact execution path — is there any window where two alerts fire?"

### What the code does
Both workers hit `SensitiveDataAlertEngine.processTriple()`. They both pass the Caffeine cache check (`noveltyCache.contains()` at line 71) — cache is empty, both get `false`. Both call `seenTriplesRepository.recordIfAbsent()` which executes:
```sql
INSERT INTO seen_triples (...) VALUES (...) ON CONFLICT (...) DO NOTHING
```
PostgreSQL serialises these at the row level. Exactly one INSERT returns `rowsAffected=1`; the other returns `rowsAffected=0`.

### The issue
There is **no window for a duplicate alert**. The guarantee is in PostgreSQL's atomic constraint enforcement, not in the application. However, there is a subtle sequence issue worth stating clearly:

```
Worker A: contains=false → recordIfAbsent=true  → markSeen → logAlert ← FIRES
Worker B: contains=false → recordIfAbsent=false → markSeen → return    ← SKIPS
```

The only race is between `contains()` and `recordIfAbsent()`. Both can enter the window simultaneously, but only one wins `rowsAffected=1`. Worker B warms the cache via `markSeen()` after the conflict, preventing future DB hits for this triple.

### Fix options
None needed for correctness. To make the guarantee more explicit:

**Option A** — Add a structured log at the conflict path:
```java
// already done in fix commit: bd649fa
log.debug("DB conflict (another node inserted first), cache warmed ...");
```

**Option B** — Add a `conflicts_total` counter metric:
```java
meterRegistry.counter("seen_triples.db.conflicts", "account", triple.accountId()).increment();
```
This makes the deduplication rate observable without parsing logs.

---

## Q3: "What happens to events already in the `LinkedBlockingQueue` if the process is killed with SIGTERM?"

### What the code does
`WorkerPoolConfig.WorkerPoolShutdown.drain()` is a `@PreDestroy` bean (lines 81-91):
```java
workerPool.shutdown();                              // stop accepting new tasks
if (!workerPool.awaitTermination(10, SECONDS)) {    // wait 10s for workers to finish
    workerPool.shutdownNow();                       // interrupt remaining workers
}
```
`EventWorker.run()` catches `InterruptedException` and returns (lines 37-40). SIGTERM triggers Spring's graceful shutdown, which calls `@PreDestroy` methods.

### The issue
`workerPool.shutdown()` stops the executor from accepting new `submit()` calls, but it does NOT drain the `LinkedBlockingQueue`. The `LinkedBlockingQueue` is independent of the executor. Workers that are mid-task finish their current batch (up to 10s window). Workers that are blocked on `queue.take()` receive an interrupt and exit immediately — leaving all batches behind them in the queue unprocessed.

**At 50k batch capacity with fast producers, hundreds of batches can be lost on a SIGTERM.**

### Fix options

**Option A — Drain the queue on shutdown (safest)**
```java
// WorkerPoolShutdown.drain()
workerPool.shutdown();
if (!workerPool.awaitTermination(10, SECONDS)) {
    // Drain remaining queue entries before interrupting
    List<IncomingBatch> remaining = new ArrayList<>();
    ingestionQueue.drainTo(remaining);  // add drainTo() to IngestionQueue interface
    log.warn("Shutdown: {} batches dropped from queue", remaining.size());
    workerPool.shutdownNow();
}
```

**Option B — Persist the queue to DB before shutdown (durable)**
On SIGTERM, flush queue contents to an `ingestion_queue` table. On startup, drain that table before accepting HTTP traffic. Requires schema change + startup drain logic.

**Option C — Accept the loss, document it**
Current approach. Acceptable for this exercise since the sensor is expected to retry on 503, and this is a crash scenario not a normal shutdown. Document: "Events in queue at SIGTERM time are lost; sensor retry handles recovery."

---

## Q4: "You use a `ConcurrentHashMap` for the services cache that never evicts. What's your upper bound on its size, and what's your OOM scenario?"

### What the code does
`ServiceCache` (line 17) holds `new ConcurrentHashMap<String, Boolean>()`. The key is `accountId + "\0" + serviceName`. An entry is added on every `PUT /services/{name}` call and on every row returned by `ServiceCacheWarmup`. The map is never cleared.

### The issue
Upper bound = `(number of distinct accounts) × (number of distinct services per account)`. In a single-tenant deployment with 100 services, this is trivially small. In a multi-tenant SaaS with 1M accounts each with 50 services = 50M entries. Each entry is ~100 bytes (String key + Boolean value + HashMap entry overhead) = ~5 GB. This is a realistic OOM risk.

The key difference from `CaffeineNoveltyCache` is intentional: Caffeine eviction on the services cache would silently treat an evicted service as private (default false), under-reporting severity — a security bug. The map must never evict.

### Fix options

**Option A — Bound per account, evict stale accounts (safest)**
```java
// Evict accounts that have had no activity in N days
// Use LoadingCache<String, ConcurrentHashMap<String, Boolean>> where outer key is accountId
// Outer cache evicts whole account subtrees on access expiry
Cache<String, Map<String, Boolean>> perAccountCache = Caffeine.newBuilder()
    .maximumSize(10_000)           // max 10k accounts in memory
    .expireAfterAccess(7, DAYS)    // evict accounts idle for 7 days
    .build();
```

**Option B — Move to DB with query cache (correct at scale)**
Remove `ConcurrentHashMap`. Add a `@Cacheable` with TTL on `ServiceRepository.findByAccount()`. Slightly higher latency on alert path (~1ms DB read vs nanosecond map lookup) but bounded memory and always consistent.

**Option C — Accept and document**
For this exercise, add a comment: "ServiceCache is unbounded — correct for single-tenant or small tenant counts; add per-account eviction before multi-tenant production use." Current approach is intentionally different from NoveltyCache.

---

## Q5: "The Caffeine novelty cache is bounded at 100k. What's the alert behaviour if an attacker sends 200k unique triples per second?"

### What the code does
`CaffeineNoveltyCache` (line 18-20) builds with `.maximumSize(100_000)`. LRU eviction removes the least-recently-used entry when capacity is exceeded. Each cache miss calls `seenTriplesRepository.recordIfAbsent()` which hits PostgreSQL.

### The issue
Under adversarial key diversity:
1. Cache hit rate → 0%. Every event hits the DB.
2. DB insert rate = event rate. At 200k unique triples/sec, DB receives 200k INSERT attempts/sec. PostgreSQL can typically handle ~10k-50k inserts/sec per node — this saturates and queues back.
3. Workers block on DB, queue fills, sensors get 503s.
4. The 100k-entry eviction cycle means popular entries are constantly evicted by adversarial entries — legitimate (novel) alerts may be missed due to DB saturation.

### Fix options

**Option A — Rate limit by account at the HTTP boundary**
```java
// EventIngestionController — reject if account exceeds N events/sec
// Use a per-account token bucket (Guava RateLimiter or Resilience4j)
RateLimiter limiter = accountRateLimiters.computeIfAbsent(accountId,
    k -> RateLimiter.create(1000.0)); // 1k events/sec per account
if (!limiter.tryAcquire()) return ResponseEntity.status(429).build();
```

**Option B — Increase cache size based on observed cardinality**
Expose `CaffeineNoveltyCache.store.stats().evictionCount()` via Actuator. If eviction count > 0 at steady state, double the cache size. The 100k constant should be `@Value("${novelty.cache.max-size:100000}")`.

**Option C — Shard the cache by account**
```java
// Per-account cache with bounded account count
LoadingCache<String, Cache<TripleKey, Boolean>> perAccountCache = ...
```
Adversarial triples in one account don't evict legitimate entries from another account.
