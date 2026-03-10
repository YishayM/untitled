# DEFENSE.md — Flow Security Event Ingestion System

## Output 1 — Decision Log

| Decision | What I chose | Alternatives considered | Why I chose this | What I'd do with more time |
|---|---|---|---|---|
| HTTP framework | Spring MVC / Tomcat (blocking) | WebFlux/Netty (reactive) | Coherent with blocking queue + fixed thread pool; no reactive mental model mismatch; simpler to reason about under load | No change — reactive adds complexity with no benefit when you already have a bounded queue as the backpressure mechanism |
| Ingestion ack model | Async queue — 202 immediately, process later | Synchronous process-then-ack | Sensor SLA is <5ms; processing involves DB writes which are 10–100ms; decoupling is mandatory | Add structured back-pressure metrics (queue depth gauge) so ops can tune queue capacity |
| Exactly-once guarantee | DB composite PK + `ON CONFLICT DO NOTHING` + Caffeine fast-path | Cache-only / distributed lock / idempotency token | Cache alone fails on restart; DB constraint is atomic across threads and nodes; no lock contention | No change to the mechanism; would add a retry queue for transient DB failures to avoid silent drops |
| Novelty cache | Caffeine bounded at 100k entries (LRU) | Unbounded HashMap / Redis / no cache | Unbounded HashMap → OOM under adversarial key diversity; Redis adds network hop on every event (defeats the purpose); Caffeine is in-process, thread-safe, bounded | Add `recordStats()` → expose hit rate via Actuator metric; tune max size based on observed distinct triple cardinality |
| Services cache | `ConcurrentHashMap` (never evicts) | Caffeine (bounded, evicts) | Caffeine eviction would silently treat a recently evicted service as private → wrong severity; services table is small and grows slowly; unbounded map is correct here | Keep ConcurrentHashMap; add a periodic reconciliation job to catch any drift vs DB |
| Alert delivery | Structured JSON log via SLF4J + logstash-logback-encoder | HTTP webhook / DB alerts table / message queue | Spec says "log"; log is zero-infrastructure, observable via any log aggregator, auditable | Add Micrometer counter per severity level so alert rate is queryable without parsing logs |
| Thread model | Fixed pool, 2×CPU cores | Virtual threads / ForkJoinPool | Workload is I/O-bound (DB inserts dominate); fixed pool + HikariCP sized to match = no connection starvation, predictable latency | Would benchmark virtual threads — they might improve throughput if many workers block concurrently on DB |
| Multi-tenancy | Row-level `account_id` composite PK everywhere | Separate schema per tenant | Simple, no DDL per tenant, supports shard-by-account-id later with no schema change | Add an index on `seen_triples(account_id)` for GET /graph scan performance as accounts grow |
| Schema migration | `spring.sql.init.mode=always` with `schema.sql` | Flyway / Liquibase | Schema is constant for this exercise; zero migration overhead | Switch to Flyway with versioned migrations before any schema change |

---

## Output 2 — Trade-off Table

| Area | Upside | Downside | Would change if... |
|---|---|---|---|
| **Memory model** | Caffeine bounded at 100k prevents OOM; ConcurrentHashMap for services is unbounded but correct | Under 100k+ unique triples, Caffeine evicts → extra DB round-trips (not duplicate alerts — still correct) | Distinct triple cardinality exceeds 100k at steady state → tune `maximumSize` based on observed cardinality |
| **Concurrency model** | Fixed pool + matching HikariCP pool = no starvation, predictable resource ceiling; `ON CONFLICT DO NOTHING` eliminates lock contention at application level | Fixed thread count hardcoded to `2×CPU` — wrong on I/O-heavy containers with high CPU quotas | Running in a container with >8 CPU cores allocated but slow DB network → switch to virtual threads |
| **State durability** | `seen_triples` survives restart in PostgreSQL — exactly-once holds across restarts; services cache rewarmed from DB on startup | Caffeine cache lost on restart — first event per triple post-restart hits DB (cold start latency spike) | SLA requires zero cold-start latency → pre-warm cache from `seen_triples` table on startup (expensive on large datasets) |
| **Error handling** | RuntimeException from alert engine is caught per batch, worker continues — one bad event doesn't kill the pool | DB failure silently drops the batch — no retry, no dead-letter queue; alert is permanently missed with only an error log | Operating in a high-reliability security context → add a retry queue (in-memory ring buffer or outbox table) for transient DB failures |
| **Test coverage** | 7 E2E scenarios cover all happy paths and edge cases; 15 unit tests cover all branching in alert engine; Testcontainers = real PostgreSQL | No load/stress tests; no chaos tests (DB kill, restart); no test for HikariCP exhaustion behaviour | Submitting to a team that runs production security alerting → add a 10k-event flood test asserting exactly-once under concurrent load |

---

## Output 3 — Anticipated Questions with Prepared Answers

> **Q: Why Spring MVC and not WebFlux? The sensor is high-frequency.**
> A: The entire processing path is blocking I/O — `queue.take()`, JDBC DB inserts, logstash appender. Wrapping blocking calls in reactive chains either blocks the event loop (wrong) or requires `publishOn(boundedElastic())` (which gives you a thread pool anyway). MVC with a bounded queue and a fixed thread pool achieves the same decoupling without reactive complexity. The <5ms ack SLA is met because POST /events does nothing but `queue.offer()` — it never touches the DB or the worker pool.

> **Q: How does exactly-once work across two concurrent workers processing the same event?**
> A: The guarantee is in PostgreSQL, not the application. `JdbcSeenTriplesRepository.recordIfAbsent()` (line 30) executes `INSERT ON CONFLICT DO NOTHING` and returns `rowsAffected`. The composite PK `(account_id, source, destination, classification)` is atomic at the DB level — exactly one of two racing threads gets `rowsAffected=1`. Only that thread fires the alert. The Caffeine cache is a read-through optimisation layered in front; it doesn't affect correctness.

> **Q: What happens on process restart?**
> A: Three things: (1) `seen_triples` survives in PostgreSQL — no duplicates on restart. (2) The Caffeine novelty cache is cold — first occurrence of each known triple after restart hits the DB, gets `inserted=0`, warms the cache, and skips the alert. Exactly-once is preserved; only latency is briefly higher. (3) The services cache (`ConcurrentHashMap`) is rewarmed from the `services` table via `ServiceCacheWarmup.onApplicationReadyEvent()` using `putIfAbsent` — it doesn't overwrite any writes that raced with the warmup.

> **Q: Is the alert replay-safe? What happens if the queue is replayed after a crash?**
> A: Yes. The `INSERT ON CONFLICT DO NOTHING` is idempotent — replaying the same event produces `inserted=0`, warms the cache, and emits no alert. The only side effect of a replay is a DB read and a cache write, both harmless. The alert log is append-only, so a replayed event that slips through (e.g., cold cache + concurrent replay) would produce a duplicate log line — not a duplicate alert in any downstream system unless that system doesn't deduplicate on `(account_id, source, destination, classification, detected_at)`.

> **Q: Who shuts down the executor, and is there a risk of lost in-flight events?**
> A: `WorkerPoolConfig.WorkerPoolShutdown.drain()` is a `@PreDestroy` bean (line 81-91). It calls `workerPool.shutdown()` to stop accepting new tasks, then waits 10 seconds for in-flight tasks to complete, then forces `shutdownNow()` which interrupts workers. `EventWorker.run()` catches `InterruptedException`, sets the interrupt flag, and returns cleanly (line 37-40). Events in the LinkedBlockingQueue that haven't been dequeued yet are abandoned — this is the known trade-off documented in DESIGN.md under "Dead-letter queue deferred."

> **Q: The Caffeine cache is bounded at 100k. What happens under adversarial key diversity — say an attacker sends 200k unique (source, destination, classification) triples?**
> A: Caffeine uses LRU eviction. The 50k entries evicted past the 100k cap fall back to the DB on next occurrence — they get `inserted=0` (already in `seen_triples`), warm the cache again, and skip. No duplicate alerts. The cost is DB round-trips for evicted entries, which increases DB load and alert latency. The `100_000` constant in `CaffeineNoveltyCache` (line 19) should be tunable via `@Value`. With `recordStats()` added in the fix commit, the eviction rate is now observable.

> **Q: How does cross-account isolation work? Could account A's public flag affect account B?**
> A: Three independent layers prevent leakage. (1) `AccountIdInterceptor` extracts `X-Account-ID` and sets it as a request attribute — every HTTP handler reads from the attribute, not the header directly. (2) `ServiceCache.key()` uses `accountId + "\0" + serviceName` — the NUL delimiter prevents `"acct-A" + "stripe.com"` colliding with `"acct-" + "Astripe.com"`. (3) All DB queries filter on `account_id`. E2E Test 7 (`publicFlagInOneAccount_doesNotAffectAnotherAccount`) proves this end-to-end.

> **Q: The `detected_at` timestamp — how accurate is it?**
> A: It's stamped at the entry to `processTriple()` (line 69), before the DB insert. This means it reflects when the worker picked up the event for processing, not when the sensor sent it or when the alert was emitted. The gap between sensor send and worker pickup is queue depth × worker throughput — at 2×CPU workers processing typical events, this is sub-second under normal load. For a SIEM use case, a forensics-grade timestamp would require stamping at queue entry time in the Tomcat thread, passing it through `IncomingBatch`, and threading it into `AlertEngine.process()`. The injected `Clock` makes this a targeted refactor.

---

## Output 4 — Acknowledged Gaps

| Gap | Justification |
|---|---|
| Dead-letter queue for DB failures | Time constraint; EventWorker drops and continues — alert is missed with error log only |
| Retry-with-backoff on DB down | DESIGN.md originally claimed this; removed in design-sync; would require circuit breaker (Resilience4j) |
| Sensor timestamp as `detected_at` | Stamped at worker dequeue, not sensor send — accurate enough for this exercise |
| Micrometer metrics on alert path | Only SLF4J DEBUG logs at state transitions; no counters queryable via Actuator |
| `seen_triples(account_id)` index | Full table scan on GET /graph; acceptable at small scale but must be added before multi-tenant production load |
| HikariCP pool size via `@Value` | Hardcoded to `2×CPU` — no per-environment override without recompile |
| Queue capacity via `@Value` | `50_000` constant in `WorkerPoolConfig` — not externalised |
| Authentication / API keys | Out of scope per spec |
| External alert delivery | Out of scope per spec |
| Flyway schema versioning | Schema is constant for this exercise |
| Raw events table | No query consumer; seen_triples satisfies "save to DB" per spec |
| Load / chaos tests | Only functional E2E tests; no throughput assertion, no DB-kill scenario |

---

## Output 5 — AI Transparency Statement

This project was built in a pair-programming session with Claude (claude-sonnet-4-6). The architecture, key design decisions, and all acceptance criteria were mine: the two-layer deduplication strategy (Caffeine + DB constraint), the choice of Spring MVC over WebFlux for coherence with the blocking queue, the NUL-delimiter isolation in ServiceCache, the `putIfAbsent` warmup race fix, and the decision to use `ON CONFLICT DO NOTHING` return value as the exactly-once signal rather than a distributed lock. I also drove the TDD approach — E2E tests were written before implementation and used as the contract for each PR.

Claude generated implementation boilerplate (JDBC DAO patterns, Spring MVC controller scaffolding, Testcontainers test setup, Gradle config), and surfaced review findings (the `Instant.now()` timestamp drift, the exception-as-control-flow in `SensitiveClassification.of()`, the asymmetric `markSeen` placement). I accepted or rejected each finding on its merits: I accepted the Clock injection and symmetric `markSeen` fixes as genuine correctness improvements; I rejected the suggestion to add full Micrometer metrics as out of scope for this exercise.

The most important bug — `@RequestParam boolean isPublic` not matching `?public=true` in the URL — was found by running the E2E tests, not by AI review. AI-generated code was validated by: (1) compiling and running all unit tests after each PR, (2) running 7 Testcontainers E2E scenarios against a real PostgreSQL instance, and (3) manually tracing the alert path for the exactly-once scenario.
