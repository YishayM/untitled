# DESIGN.md — Flow Security Event Ingestion System

## System Context

**Callers:**
- Sensor (high-frequency): POST /events — millions of events/sec, expects <5ms ack
- Ops clients (low-frequency): PUT /services/{name}, GET /graph

**This system → sinks:**
- PostgreSQL (seen_triples, services tables) — persistent state
- stdout / log file (structured JSON alert lines) — alert delivery

**SLAs:**
- POST /events → 202 Accepted: <5ms (just enqueue, no processing)
- Alert log latency: best-effort, seconds-level acceptable
- PUT /services / GET /graph: no tight SLA

**Architecture diagram:** `![Architecture](architecture.excalidraw.png)`

---

## Data Flow

```
[Sensor]
  Header: X-Account-ID: <string>
  POST /events  →  [Tomcat Thread]  →  202 Accepted  (path ends here for sensor)
                          ↓
               queue.offer(batch)  →  503 if queue full (50k cap)
                          ↓
                  [LinkedBlockingQueue]
                   capacity: 50,000 batches
                          ↓
                  [Worker Thread Pool]
                   size: 2 × Runtime.availableProcessors()
                   (I/O-bound: DB writes dominate)

  for each event in batch:
    for each (key → classification) in event.values:
      1. if classification NOT in {FIRST_NAME, LAST_NAME,
                                   CREDIT_CARD_NUMBER, SOCIAL_SECURITY_NUMBER}
         → skip (not sensitive)

      2. Check Caffeine cache key=(accountId, source, dest, classification)
         → HIT  → skip (fast path, no DB call)
         → MISS → continue

      3. INSERT INTO seen_triples (account_id, source, destination, classification)
         ON CONFLICT DO NOTHING
         → inserted = 0 or 1 (atomic)

      4. Update Caffeine cache (markSeen) unconditionally — symmetric for both branches
         Conflict branch: cache warm prevents repeated DB round-trips for the same triple
         Inserted branch: normal warm-up before alert emission

      5. If inserted = 0  → another thread/node won; cache already warmed → skip

      6. Lookup services ConcurrentHashMap for source and destination
         → either is_public=true  → severity = HIGH
         → both is_public=false   → severity = MEDIUM

      7. LOG alert (JSON, one line):
         {"type":"SECURITY_ALERT","account_id":"...","source":"...","destination":"...",
          "classification":"...","severity":"HIGH|MEDIUM","detected_at":"<ISO-8601>"}
         detected_at stamped at entry to step 3 (before DB latency) via injected Clock

[Ops Client]
  PUT /services/{name}?public=true  +  X-Account-ID
    → UPSERT services table
    → Update ConcurrentHashMap immediately (synchronous, on Tomcat thread)

  GET /graph  +  X-Account-ID
    → SELECT source, destination, classification FROM seen_triples WHERE account_id=?
    → Aggregate into vis.js payload: { nodes: [...], edges: [...] }
```

---

## Key Design Decisions

| Decision | Options Considered | Choice | Rationale | Trade-off |
|---|---|---|---|---|
| HTTP framework | WebFlux/Netty (reactive) vs MVC/Tomcat (blocking) | Spring MVC | Coherent with blocking queue + thread pool; no reactive complexity for a home task | Lower theoretical max throughput vs full reactive; acceptable at this scale |
| Ingestion ack | Sync process vs async queue | Async queue (LinkedBlockingQueue) | Sensor never blocks on processing; <5ms ack guaranteed | Alert latency decoupled from ingestion; backpressure via 503 |
| Exactly-once guarantee | Cache only / DB constraint only / both | DB unique constraint + cache fast-path | Cache alone fails on restart or under race; DB ON CONFLICT is atomic | Two-phase check; cache miss costs one DB roundtrip |
| Alert delivery | HTTP API / DB table / structured log | Structured log (SLF4J + logstash-logback-encoder) | Zero infrastructure; observable; auditable; no extra API surface | Not queryable after the fact — acceptable per requirement |
| Services lookup | DB query per alert / in-memory map | ConcurrentHashMap warmed at startup | services table is small and infrequently updated; eliminates DB hotspot on alert path | Stale for milliseconds after PUT — acceptable (synchronous invalidation on write) |
| Seen-triples cache | None / Caffeine bounded / unbounded HashMap | Caffeine maximumSize(100_000) | Unbounded HashMap causes OOM under adversarial workloads; Caffeine is thread-safe LRU | Cache eviction under memory pressure means DB fallback — still correct, just slower |
| Schema migration | Flyway / Liquibase / schema.sql | schema.sql (spring.sql.init.mode=always) | Schema is constant for this exercise; no versioning overhead needed | Not suitable for production schema evolution |
| Multi-tenancy | Separate schema / row-level account_id | Row-level account_id composite PK everywhere | Simple; no DDL per tenant; supports sharding later by account_id range | Every query must include account_id — enforced by convention |
| Events persistence | Store raw events table / skip | Skip (no events table) | No query path consumes raw events; seen_triples captures all meaningful derived state; raw storage adds high-volume write with no consumer | Spec says "save to DB" — seen_triples satisfies this; raw event history not queryable |
| Worker pool sizing | Virtual threads / ForkJoinPool / fixed pool | Fixed pool (2 × CPU cores) | Workload is I/O-bound (DB inserts); fixed pool with HikariCP sized to match prevents connection starvation | CPU-bound phases (classification check) would benefit from ForkJoinPool — not the bottleneck here |

---

## Schema

```sql
-- Tenant isolation: account_id is part of every primary key
-- No foreign key between tables — account_id is an opaque string

CREATE TABLE IF NOT EXISTS services (
    account_id   TEXT    NOT NULL,
    service_name TEXT    NOT NULL,
    is_public    BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, service_name)
);

CREATE TABLE IF NOT EXISTS seen_triples (
    account_id     TEXT NOT NULL,
    source         TEXT NOT NULL,
    destination    TEXT NOT NULL,
    classification TEXT NOT NULL,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, source, destination, classification)
    -- ^^ This composite PK IS the exactly-once guarantee.
    -- INSERT ON CONFLICT DO NOTHING is the atomic deduplication guard.
);
```

**Why no events table:** Raw event persistence adds the highest-volume write in the system (every event, not just novel triples) with no query consumer. `seen_triples` is the authoritative persistent record of what has been observed. Deferred until a concrete query requirement exists.

---

## Failure Modes

| Failure | Behavior | Acceptable? |
|---|---|---|
| Queue full (50k cap reached) | POST /events returns 503; sensor retries | Yes — sensor retry expected; protects worker pool |
| DB down during novelty check | Worker catches RuntimeException, logs error, and drops the in-flight batch; no retry | Acknowledged trade-off — dead-letter queue deferred |
| Two workers race on same triple | DB ON CONFLICT DO NOTHING — one inserts, one skips; exactly one alert | Yes — exactly-once guaranteed by DB constraint |
| Process restart | Caffeine cache lost; services cache rewarmed from DB; seen_triples survives in DB | Yes — DB is source of truth; cache is optimization only |
| Unknown classification arrives | Treated as non-sensitive; no alert fired | Yes — per spec, only defined types are sensitive |
| X-Account-ID header missing | 400 Bad Request | Yes |
| Event with >100 value fields | 400 Bad Request at HTTP boundary (before queuing) | Yes — prevents worker starvation from adversarially wide events |
| services ConcurrentHashMap stale by milliseconds after PUT | Next alert after PUT may use old flag | Yes — synchronous update on write path minimizes window |
| GET /graph on large account | Full table scan on seen_triples for account | Acceptable for this scale; add index if needed |

---

## Concurrency Model

```
Tomcat thread pool  (handles HTTP, bounded by server.tomcat.threads.max)
  │
  ├── POST /events → queue.offer() → return 202/503   [non-blocking]
  ├── PUT /services → DB upsert + HashMap.put()        [fast, synchronous]
  └── GET /graph   → DB query + response               [fast, synchronous]

Worker thread pool  (fixed, 2 × CPU cores)
  └── queue.take() → classify → cache check → DB insert → log alert
      [all blocking I/O here, isolated from HTTP threads]

HikariCP pool size = worker thread count
  → No worker ever waits for a connection; no connections wasted
```

---

## Thread Safety Guarantees

| Shared State | Type | Thread Safety Mechanism |
|---|---|---|
| `seen_triples` novelty | Caffeine cache | Thread-safe by design (Caffeine) |
| `seen_triples` persistence | PostgreSQL | `ON CONFLICT DO NOTHING` — atomic at DB level |
| `services` flag lookup | ConcurrentHashMap | Lock-free reads; volatile writes on PUT |
| `services` persistence | PostgreSQL | UPSERT — last write wins |
| Alert log | SLF4J Logger | Thread-safe by SLF4J contract |

---

## Multi-Account Design

- All tables include `account_id TEXT NOT NULL` as leading PK column
- All service layer methods accept `accountId` as first parameter
- HTTP middleware extracts `X-Account-ID` header and injects into request context
- In-memory caches keyed by `(accountId, ...)` composite — no cross-account leakage possible
- Horizontal scaling: shard by `account_id` hash range when needed — no schema change required

---

## What I Explicitly Did NOT Build

| Omission | Justification |
|---|---|
| Authentication / API keys | Out of scope per spec |
| GET /alerts endpoint | Cancelled per requirement — log is the delivery mechanism |
| Retroactive re-alerting on public flag change | Not required — alerts reflect flag at time of processing |
| Dead-letter queue for failed async jobs | Time constraint; DB retry on next delivery is sufficient |
| Horizontal pod deduplication | Single-node; DB constraint handles races within node |
| Raw events table | No query consumer; seen_triples satisfies "save to DB" |
| Flyway / schema versioning | Schema is constant for this exercise |
| External alert delivery (email, webhook) | Out of scope |

---

## Open Questions (Resolved)

| # | Question | Resolution |
|---|---|---|
| 1 | Alert SLA after ingestion? | ASSUMED: best-effort, seconds-level |
| 2 | Unknown classifications? | ASSUMED: non-sensitive, ignored |
| 3 | Self-loops (source == destination)? | ASSUMED: valid, processed normally |
| 4 | account_id format? | ASSUMED: opaque string, no validation |
| 5 | Retroactive re-alert on flag change? | ASSUMED: no |
| 6 | Queue overflow behavior? | 503 to sensor, sensor retries |

---

## PR Stack

| PR | Branch | Title | Scope | Priority |
|---|---|---|---|---|
| 1 | `pr1/skeleton` | `feat: project skeleton, Docker, schema, DESIGN.md` | Gradle, Dockerfile, docker-compose, schema.sql, actuator/health, CLAUDE.md, DESIGN.md, README | must-ship |
| 2 | `pr2/domain` | `feat: domain model, services table, PUT /services API` | SensitiveClassification enum, Event/TripleKey/ServiceInfo records, ServiceRepository, PUT /services, services ConcurrentHashMap warmed on startup | must-ship |
| 3 | `pr3/ingestion` | `feat: async ingestion pipeline` | POST /events, LinkedBlockingQueue(50k), fixed worker pool, X-Account-ID middleware, 503 on overflow | must-ship |
| 4 | `pr4/alert-engine` | `feat: novelty check and exactly-once alert logging` | Caffeine cache, seen_triples INSERT ON CONFLICT, severity resolution via services cache, JSON log alert | must-ship |
| 5 | `pr5/graph-api` | `feat: graph API — Bonus I` | GET /graph, vis.js nodes+edges from seen_triples | nice-to-have |
| 6 | `pr6/integration-tests` | `test: integration tests — Bonus II` | Testcontainers + PostgreSQL, 6 scenarios | nice-to-have |
