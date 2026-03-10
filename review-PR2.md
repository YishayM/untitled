# Code Review — PR2: domain model, services table, PUT /services API

Reviewer role: Staff Engineer, Distributed Systems

---

## MUST FIX

**MUST FIX** | `ServiceCache.java:21` | **Unbounded ConcurrentHashMap — OOM risk under adversarial workloads.**
`store` is a raw `ConcurrentHashMap<String, Boolean>` with no size cap. An adversary (or misconfigured sensor) that rotates `accountId` or `serviceName` values will grow this map without bound. DESIGN.md explicitly calls out that the Caffeine seen-triples cache is bounded at 100,000 entries _because_ an unbounded HashMap causes OOM — the same reasoning applies here. Use Caffeine (or at minimum a `LinkedHashMap` with LRU eviction) with a configurable `maximumSize`. Services are described as "small and infrequently updated" in DESIGN.md, but the cache imposes no enforcement of that assumption.

**MUST FIX** | `ServiceController.java:27` | **`@RequestHeader` on `ServiceController` is redundant and fragile — the interceptor can be bypassed independently of the controller.**
`AccountIdInterceptor` already enforces the header; `ServiceController` re-declares `@RequestHeader("X-Account-ID")` as a method parameter. This creates two separate enforcement points that can drift (e.g., if the interceptor is removed or path patterns change). More critically, Spring will throw a `MissingRequestHeaderException` (500, not 400) if the header is absent and the interceptor somehow did not fire — inconsistent with the documented 400 contract. The controller should read `accountId` from a request-scoped attribute set by the interceptor, not re-bind the raw header. This is also the pattern required to support PR3's POST /events endpoint uniformly.

**MUST FIX** | `ServiceController.java:29-30` | **Non-atomic write: DB upsert and cache update are not a single atomic operation — cache can diverge permanently on DB failure.**
`serviceRepository.upsert(...)` is called first; if it succeeds but a subsequent `RuntimeException` (e.g., from a misconfigured connection pool) interrupts the thread before `serviceCache.put(...)` executes, the DB and in-memory cache are permanently inconsistent for this service until the next restart warmup. The DB is the source of truth; the cache must be updated _only after_ a confirmed DB write, and a failure between the two must not leave the cache stale indefinitely. Either wrap both in a try/finally that guarantees a cache refresh on failure, or — better — treat the cache as write-through at warmup time only and have workers re-read from DB on miss (which is already the pattern for seen-triples).

**MUST FIX** | `ServiceCacheWarmup.java:24` | **Warmup race: a concurrent PUT /services can write to DB between the time `findAll()` starts reading and the cache is fully populated, causing the cache to silently miss that write.**
`warmUp()` calls `serviceRepository.findAll()` (a point-in-time snapshot), then iterates and populates the cache. If a PUT /services request is processed on a Tomcat thread _during_ this iteration — after `findAll()` returns but before `serviceCache.put()` is called for that service — the sequence is: (1) `findAll()` does not include the new row, (2) controller writes to DB, (3) controller writes to cache, (4) warmup overwrites the cache entry from stale snapshot. Net result: cache holds stale `is_public=false` for a service that was just set `is_public=true`. Because warmup runs at `ApplicationReadyEvent`, the server is already accepting traffic. Fix: either lock out traffic during warmup (impractical), apply warmup entries only if absent (`putIfAbsent`), or re-read from DB after warmup to self-heal.

**MUST FIX** | `SensorEvent.java:8` | **`values` field uses raw `Map<String, String>` — missing validation that the map is non-null.**
`SensorEvent` is a record whose `values` map will be iterated in the event-processing pipeline (PR3/PR4). A null `values` will throw a NullPointerException deep in the worker thread with no useful context. A compact constructor should validate non-null (and optionally non-empty) values at construction time, or the type should be `Map<String, String>` with a `@NotNull` constraint enforced at the deserialization boundary.

---

## SHOULD FIX

**SHOULD FIX** | `ServiceCache.java:31` | **Key collision via colon in `accountId` or `serviceName`.**
The composite cache key is `accountId + ":" + serviceName`. If `accountId="a:b"` and `serviceName="c"` this produces the same key as `accountId="a"`, `serviceName="b:c"`. DESIGN.md describes `account_id` as an "opaque string" with no validation. Use a delimiter that cannot appear in either field (e.g., `\0`), or use a proper record/pair as the map key rather than string concatenation.

**SHOULD FIX** | `JdbcServiceRepository.java:24` | **`updated_at = NOW()` called twice in the same SQL — DB-side clock skew between the two calls is theoretically possible.**
The INSERT branch sets `updated_at = NOW()` and the ON CONFLICT branch also calls `NOW()`. In practice PostgreSQL evaluates both calls within the same statement and they return the same value, but this is a subtle correctness assumption. Use `updated_at = EXCLUDED.updated_at` in the DO UPDATE clause to guarantee the value is identical to what was inserted.

**SHOULD FIX** | `ServiceCacheWarmup.java:24` | **No log at warmup start/end — silent failure or empty-DB scenario is unobservable.**
If `findAll()` throws (e.g., schema not yet initialized, transient DB connectivity issue at startup), the exception propagates through the Spring event system and will likely halt startup — but the error message will not indicate which component failed or how many services were loaded. Log at INFO before and after warmup: `"ServiceCache warmup starting"` / `"ServiceCache warmup complete, loaded {} services"`. If the exception must be swallowed (e.g., to allow startup with empty DB), log at ERROR with the exception; silently swallowing it violates the "structured logs at significant decision points" rule from CLAUDE.md.

**SHOULD FIX** | `ServiceController.java:31` | **Response code is `200 OK` for a PUT that creates or updates — should be `204 No Content`.**
`ResponseEntity.ok().build()` returns HTTP 200. An upsert returns no body; the semantically correct code is 204. More importantly, the caller cannot distinguish a create from an update (201 vs 200 is standard REST practice), though for this use case 204 is acceptable. At a minimum, return 204 rather than 200 to avoid implying there is a body.

**SHOULD FIX** | `ServiceRepository.java:13` | **`findAll()` has no safety contract if called before schema is initialized.**
`findAll()` runs a raw `SELECT` against `services`. If called before `schema.sql` has executed (e.g., in a test or if `spring.sql.init.mode` is misconfigured), it throws `BadSqlGrammarException` with a `services` table-not-found error. The Javadoc should document this precondition, and `ServiceCacheWarmup.warmUp()` should catch `DataAccessException` and log an error rather than letting the exception propagate and kill startup — especially since an empty services table on first boot is a fully valid state (DESIGN.md Failure Modes table does not list DB-down-at-warmup).

**SHOULD FIX** | `SensorEvent.java:5-10` | **`date` field name is semantically weak and the type is `String` rather than a temporal type.**
The field named `date` carries no indication of what moment it represents (event time? ingest time? sensor local time?). CLAUDE.md's Newspaper Analogy rule ("no vague names") applies to fields too. Rename to `eventTimestamp` or `occurredAt` and parse to `Instant` at the deserialization boundary, or at minimum document the expected format. A raw `String` will cause silent misinterpretation when the field is used in alert payloads (PR4).

---

## CONSIDER

**CONSIDER** | `SensitiveClassification.java:14-15` | **`SENSITIVE_NAMES` is computed from `values()` at class-load time — this is correct and efficient, but `isSensitive()` is now a redundant wrapper around `of().isPresent()`.**
Both `isSensitive(String)` and `of(String)` exist. `isSensitive` does a set lookup; `of` does `valueOf` with exception catch. They are consistent but diverge if a future enum value is added and one path is missed. Consider removing `isSensitive` and having callers use `of(...).isPresent()`, or at minimum make `isSensitive` delegate to `of`.

**CONSIDER** | `ServiceCache.java:27` | **`isPublic` returning a primitive `boolean` loses the distinction between "service not registered" and "service registered as private".**
`getOrDefault(..., false)` means an unknown service and a known-private service are indistinguishable to callers. In the severity-resolution logic (PR4), a service that has never been registered should arguably be treated as "unknown/private" — which is the current behavior — but this assumption should be explicit. Consider returning `Optional<Boolean>` so the alert engine can separately handle "unknown" vs "known private" if requirements evolve.

**CONSIDER** | `ServiceCacheWarmup.java:23` | **`@EventListener(ApplicationReadyEvent.class)` runs on the main thread synchronously — a slow or large `findAll()` delays server readiness signal.**
For a "small" services table this is fine. If the services table grows (multi-tenant with thousands of accounts), warmup could take seconds and block the readiness probe. Consider running warmup on a background thread and marking a `warmedUp` flag that health checks can consult.

**CONSIDER** | `WebConfig.java:19` | **Interceptor excludes `/actuator/**` but not `/error` — Spring's default error page at `/error` will fail with a 400 if the X-Account-ID header is absent when Spring redirects internally to `/error`.**
When a handler throws and Spring redirects to `/error`, the interceptor runs again on the forward. If the original request lacked the header (which triggered the 400), the interceptor fires again, potentially interfering with the error response. Add `/error` to `excludePathPatterns`.

**CONSIDER** | `ServiceCacheTest.java:38-44` | **Missing test: account isolation when `serviceName` contains a colon.**
The collision risk identified in the SHOULD FIX for key construction is not covered by any test. A test like `cache.put("a", "b:c", true); assertThat(cache.isPublic("a:b", "c")).isFalse()` would immediately expose the bug.

**CONSIDER** | `SensitiveClassificationTest.java` | **Missing test: `of()` returns empty for blank string.**
`isSensitive("")` is tested and returns false. But `of("")` is not tested — `valueOf("")` will throw `IllegalArgumentException`, which is caught and returns `Optional.empty()`. This is correct, but the blank-string path through `of()` is an uncovered branch.

**CONSIDER** | `ServiceCacheTest.java` | **Missing concurrent-update test for `put` then `isPublic` — visibility guarantee is untested.**
The tests are all single-threaded. The `ServiceCache` Javadoc claims "lock-free reads" and thread safety, but no test exercises concurrent `put` + `isPublic` from multiple threads to validate that `ConcurrentHashMap` provides the expected visibility. A brief `CompletableFuture`-based concurrent test would validate the threading contract.

**CONSIDER** | `ServiceController.java` | **No input validation on `serviceName` path variable or `isPublic` query param — malformed requests reach the DB.**
An empty `serviceName` (e.g., `PUT /services/`) routes to a 404 via Spring MVC, but a `serviceName` containing SQL-special characters is passed directly to `JdbcTemplate` as a parameter (safe via prepared statement). However, an extremely long `serviceName` will be truncated silently by PostgreSQL's `TEXT` type — or could cause index bloat. Consider `@Size` validation at the controller boundary.

---

## Summary of Distributed Systems Checks

| Check | Finding |
|---|---|
| ServiceCache unbounded growth | **MUST FIX** — no size limit on ConcurrentHashMap |
| ServiceCacheWarmup resilience on empty DB | **SHOULD FIX** — exception propagates; should be caught and logged; empty table is valid first-boot state |
| JdbcServiceRepository upsert idempotency | Correct — ON CONFLICT DO UPDATE is idempotent; `NOW()` duplication is minor (SHOULD FIX) |
| Warmup vs concurrent PUT race | **MUST FIX** — warmup can overwrite a concurrent cache write with stale data |
| AccountIdInterceptor blank vs null | Correct — `accountId == null \|\| accountId.isBlank()` covers both cases |
| ServiceRepository.findAll() before schema init | **SHOULD FIX** — DataAccessException must be handled in warmup, not propagated |
| Blocking calls on Tomcat threads | ServiceController makes a synchronous DB upsert on a Tomcat thread — acceptable per DESIGN.md for the low-frequency ops path; no issue in PR2 scope |
| Structured logs at decision points | **SHOULD FIX** — warmup has no logging; controller has no logging on upsert |
| Missing test coverage | `ServiceCacheTest`: no key-collision test, no concurrency test. `SensitiveClassificationTest`: no `of("")` test |

---

Total: **5 MUST FIX, 6 SHOULD FIX, 7 CONSIDER**
