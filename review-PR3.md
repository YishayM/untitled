# Code Review — PR3: Async Ingestion Pipeline

Reviewer perspective: staff engineer, distributed systems focus.

---

## MUST FIX

**MUST FIX | EventWorker.java:31 | RuntimeException from alertEngine.process() kills the worker thread permanently**

`alertEngine.process()` is called inside a `try` block that only catches `InterruptedException`. Any `RuntimeException` (e.g., `NullPointerException`, `IllegalArgumentException`, or any future real implementation's DB exception surfacing as unchecked) propagates out of `run()`. The `ExecutorService` silently swallows it — the thread dies, the pool shrinks by one worker, and no new replacement is submitted. Under adversarial or buggy conditions all N_WORKERS threads die and the queue fills to 50 k, causing sustained 503s with no recovery path short of restart. The fix is a `catch (RuntimeException e)` inner block that logs the exception at ERROR level with batch context and continues the loop. The outer `InterruptedException` catch must remain separate and must not be broadened to `Exception` (that would swallow interrupts).

**MUST FIX | EventIngestionController.java:29 | Null accountId silently enqueued when interceptor is bypassed**

`request.getAttribute("accountId")` returns `null` if the interceptor did not run. Spring MVC interceptors are bypassed for unhandled exceptions routed through `/error` and for any path not matched by a `DispatcherServlet` mapping. More concretely: any test that calls the controller directly (e.g., `MockMvc` without `addInterceptors`) or calls the endpoint without `AccountIdInterceptor` in the chain will produce an `IncomingBatch` with `accountId == null`. That null propagates into the worker thread, into `AlertEngine.process()`, and eventually into a DB query as a literal `null` account ID — corrupting multi-tenant isolation silently. The controller must assert non-null before constructing `IncomingBatch`, returning 400 or throwing `IllegalStateException`. The CLAUDE.md rule "No null returns" implies this; the rule should be extended to "no null fields on domain objects."

**MUST FIX | IncomingBatch.java:11–14 | events() list is mutable — caller can mutate the batch after offer()**

`IncomingBatch` is declared as a `record` and documented as "immutable," but `List<SensorEvent> events` is stored and returned as-is. The HTTP deserialization layer produces a mutable `ArrayList`. After `queue.offer(batch)`, the Tomcat thread returns, but the Spring deserialization infrastructure may reuse or GC the list normally — however nothing in the contract prevents a caller (in tests or via a future refactor) from mutating the list between `offer()` and `take()`. The correct fix is to wrap in `List.copyOf()` at construction time in the canonical constructor:
```java
public IncomingBatch {
    events = List.copyOf(events); // defensive copy; also rejects null elements
}
```
This is consistent with the "immutable unit of work" Javadoc and with the CLAUDE.md "no raw types / no mutable shared state" spirit.

**MUST FIX | NoOpAlertEngine.java:14 | @ConditionalOnMissingBean(name=...) bean name is wrong — stub will never be displaced**

`@ConditionalOnMissingBean(name = "sensitiveDataAlertEngine")` checks for a bean named `"sensitiveDataAlertEngine"`. Spring derives a bean name from a `@Component`-annotated class by lowercasing the first letter of the simple class name. The real PR4 implementation will almost certainly be named something like `AlertEngineImpl` → Spring bean name `"alertEngineImpl"`, not `"sensitiveDataAlertEngine"`. Unless PR4 uses `@Component("sensitiveDataAlertEngine")` explicitly, the condition will always be false (the named bean is never found), and `NoOpAlertEngine` will always be registered alongside the real implementation — causing an `NoUniqueBeanDefinitionException` at startup (or, worse, the wrong implementation winning injection). Options: use `@ConditionalOnMissingBean(AlertEngine.class)` (matches on type, not name — the correct idiom for interface-level stubs), or coordinate an explicit `@Component("sensitiveDataAlertEngine")` on the PR4 class.

---

## SHOULD FIX

**SHOULD FIX | WorkerPoolConfig.java:15–16 | public static constants expose internal sizing policy as API**

`N_WORKERS` and `QUEUE_CAPACITY` are `public static final` fields on a `@Configuration` class. This means any code in the codebase (including tests) can read and depend on these values. `N_WORKERS` is especially dangerous because it is evaluated once at class-loading time via `Runtime.getRuntime().availableProcessors()` — if this config class is loaded during a test on a machine with 2 cores, `N_WORKERS == 4`, but `WorkerStarter` will submit 4 workers. If a test pre-warms the pool, submission happens twice (see next finding). Reduce visibility to `package-private` (drop `public`) or expose them only via `@Value`-bound config properties. This also makes them overridable per environment without recompilation.

**SHOULD FIX | WorkerStarter.java:34–37 | No guard against double-submission — calling run() twice spawns duplicate workers**

`WorkerStarter.run()` unconditionally submits `N_WORKERS` tasks. `ApplicationRunner` is called once per application context, so in production this is safe. However: (a) integration tests that refresh the context between tests will call `run()` again and double the workers in a shared pool; (b) any future programmatic context refresh (e.g., graceful reload) will do the same. A simple `AtomicBoolean started` guard (check-and-set with `compareAndSet`) costs nothing and prevents this class of bug. Alternatively, submit workers in a `@PostConstruct` + `@PreDestroy` lifecycle pair that is inherently single-call.

**SHOULD FIX | application.properties:4 | HikariCP pool size (8) is hardcoded and decoupled from N_WORKERS**

DESIGN.md (Concurrency Model section) explicitly states: "HikariCP pool size = worker thread count — No worker ever waits for a connection; no connections wasted." The code violates this: `hikari.maximum-pool-size=8` is a static constant in properties while `N_WORKERS = 2 × Runtime.getRuntime().availableProcessors()`. On a machine with 6 cores, `N_WORKERS = 12` but HikariCP caps at 8 — 4 workers will contend for connections, increasing alert latency and potentially causing HikariCP timeout exceptions. On a 2-core machine `N_WORKERS = 4` and HikariCP wastes 4 idle connections. Set `spring.datasource.hikari.maximum-pool-size=${WORKER_POOL_SIZE:8}` and drive both from the same environment variable or Spring property.

**SHOULD FIX | EventIngestionController.java:33 | No structured log at 503 (queue-full) path**

The 503 response is returned silently. In production, queue saturation is a critical operational signal — it means the worker pool is backed up and the system is shedding load. Without a log line at WARN or ERROR level (with queue depth if available), operators have no visibility into saturation events until they notice elevated error rates externally. Add a single `log.warn("Ingestion queue full, returning 503; accountId={}", accountId)` before the return. If `IngestionQueue` exposes a `size()` method, include it.

**SHOULD FIX | WorkerPoolConfig.java:28 | destroyMethod="shutdownNow" drops in-flight batches on context close**

`shutdownNow()` interrupts running threads immediately and returns the list of queued-but-not-yet-started tasks — these tasks are silently discarded. On graceful shutdown (SIGTERM from Docker/Kubernetes), any batches in the `LinkedBlockingQueue` that haven't yet been picked up by a worker are lost. The correct sequence for graceful drain is: (1) `workerPool.shutdown()` — no new tasks accepted but queued tasks drain; (2) `workerPool.awaitTermination(timeout)` — wait for in-flight work to complete; (3) `workerPool.shutdownNow()` if timeout exceeded. Consider a custom `@PreDestroy` method that performs this sequence with a configurable timeout (e.g., 30 s).

---

## CONSIDER

**CONSIDER | BoundedIngestionQueue.java | Expose size() or remainingCapacity() for observability**

`LinkedBlockingQueue` provides `size()` and `remainingCapacity()`. Neither is exposed by `IngestionQueue`. Adding these to the interface (or a separate `ObservableQueue` sub-interface) would enable: (a) the 503 log line to include queue depth (see SHOULD FIX above); (b) a Micrometer gauge that tracks queue utilization over time — critical for capacity planning at millions-of-events-per-second SLA.

**CONSIDER | EventWorker.java:30 | Iterate batch.events() via index or stream — consider empty-batch short-circuit**

A batch with zero events (valid per the current contract — `List.of()` is accepted in tests) results in `queue.take()` waking up, allocating an iterator, and returning immediately. Under high load this wastes a scheduling quantum per empty batch. A short-circuit `if (batch.events().isEmpty()) continue;` (or validation at `offer()` time rejecting empty batches with 400) is cheap and improves observability by keeping the worker log clean.

**CONSIDER | BoundedIngestionQueueTest.java | Tests do not cover the null-accountId or mutable-events failure modes**

The existing tests cover capacity bounds and FIFO ordering — good baseline. Missing coverage: (1) `IncomingBatch` with null `accountId` — should this be rejected at construction? (2) Mutation of the event list after `offer()` — a test that mutates the list and then `take()`s should assert the batch is unaffected. These tests would have caught both MUST FIX issues above at the unit level.

**CONSIDER | WorkerStarter.java | Log thread names or pool state after submission for startup observability**

The startup log (`"Started {} event workers"`) is useful, but does not confirm the workers are actually running (submit is async). Consider logging inside `EventWorker.run()` at INFO when the loop starts — which already exists — and correlating it with a structured startup health check or `ApplicationReadyEvent` listener that confirms all workers have started within a timeout.

---

Total: 4 MUST FIX, 5 SHOULD FIX, 4 CONSIDER
