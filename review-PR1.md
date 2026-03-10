# PR1 Review — Staff Engineer (Distributed Systems)

---

## MUST FIX

**MUST FIX | E2ETest.java:249 — Raw type `Map` used in graph API test**
`ResponseEntity<Map>` and `Map<String, Object> body = (Map<String, Object>) response.getBody()` use the raw `Map` type for the `ResponseEntity` type parameter. This violates the explicit project rule in CLAUDE.md ("No raw types") and produces an unchecked cast. Use `ResponseEntity<Map<String, Object>>` and remove the redundant cast.

**MUST FIX | E2ETest.java:201-211 — Thread.sleep(2000) is an unreliable negative-absence proof**
Test 4 asserts no alert fires by sleeping 2 000 ms and then checking the log. This is fundamentally flawed in two directions: (a) on a slow CI/CD machine or container with CPU throttling, 2 000 ms is not enough for the async pipeline to finish processing — the assertion becomes vacuously true because the worker hasn't run yet; (b) on a fast machine the sleep wastes wall-clock time for no benefit. The correct pattern is to synchronize on a known observable side-effect (e.g., await the HTTP 202 being returned and then await a sentinel log line such as "processed batch" emitted by the worker after completing the batch), then assert no SECURITY_ALERT appeared. Until an observable completion signal exists in the production code, this test cannot be made deterministic.

**MUST FIX | E2ETest.java:64 — Single shared `ACCOUNT` constant breaks parallel-safe test isolation**
All six tests share `static final String ACCOUNT = "acct-test"`. The `@BeforeEach` cleanDb deletes rows, but JUnit 5 does not guarantee sequential execution — with `@TestMethodOrder` absent, execution order is deterministic per JVM but parallel execution (`junit.jupiter.execution.parallel.enabled=true`) would cause inter-test row contamination and log-capture cross-pollution. Even sequentially, if any future maintainer enables parallelism this silently breaks. Each test should use a unique account ID (e.g., a UUID generated in `@BeforeEach`) so isolation is structural, not order-dependent.

**MUST FIX | E2ETest.java:109-114 — OutputCaptureExtension log-bleed between tests**
`OutputCaptureExtension` captures stdout from the moment the extension is activated (before `@BeforeEach`). The `@BeforeEach` cleans the DB but does NOT reset the captured output buffer. Async worker threads from a previous test that were still in-flight when that test ended (e.g., log lines delayed by GC or thread scheduling) can appear in the *next* test's `output`. Tests 1, 2, 5 all assert `doesNotContain("HIGH")` or severity-specific strings — a stray log line from a previous test's processing can cause false failures or false passes. Each test that cares about the exact content of alerts (not just "at least one") must either: (a) record a capture baseline at the start of the test and compare only lines after it, or (b) use a counter strategy that filters by unique fields (source/destination) so cross-test leakage is automatically excluded.

---

## SHOULD FIX

**SHOULD FIX | E2ETest.java:190 — Thread.sleep(1500) in Test 3 is fragile on slow CI**
After `awaitAlert()` confirms the first alert arrived, the test sleeps 1 500 ms to let spurious duplicates surface before counting. On a CI machine with CPU throttling or under JVM GC pause, 1 500 ms may not be enough for a slow worker to emit a second alert if it is going to. This is especially dangerous when the test is meant to prove exactly-once: a flaky false-pass is worse than a flaky failure. Replace with Awaitility polling that waits until count has been stable for two consecutive intervals (e.g., poll every 100 ms, assert count stays at 1 for a full second after the first alert).

**SHOULD FIX | application.properties:6 — `spring.sql.init.mode=always` is documented but still risky**
DESIGN.md acknowledges this is not suitable for production. The schema uses `CREATE TABLE IF NOT EXISTS`, so repeated restarts are safe against table-already-exists errors. However, if the schema ever adds a `DROP` statement or an index `CREATE` without `IF NOT EXISTS`, a restart would cause data loss or an error that prevents startup. This is acceptable for a demo, but the review file (`review-PR1.md`) should note that any future schema change must be audited against idempotency, and `IF NOT EXISTS` must be enforced on every DDL object going forward.

**SHOULD FIX | docker-compose.yml — No named volume for PostgreSQL; data lost on `docker compose down`**
The `postgres` service has no `volumes:` stanza. Every `docker compose down` (not `stop`) destroys all data. This is fine for local dev, but makes any multi-session manual test or demo brittle. Add a named volume (`pgdata:/var/lib/postgresql/data`) and declare it under the top-level `volumes:` key.

**SHOULD FIX | Dockerfile:3 — Hardcoded jar name `flow-security-1.0.0.jar` couples to `build.gradle.kts` version**
`build.gradle.kts` sets `version = "1.0.0"`. If the version is bumped, the Dockerfile silently copies a non-existent file and the build produces a broken image with no useful error. Use `COPY build/libs/*.jar app.jar` (safe here because Gradle produces exactly one jar in that directory with the Spring Boot plugin and the `bootJar` task), or use a build arg.

**SHOULD FIX | schema.sql — Missing index on `seen_triples(account_id)` for graph query**
The GET /graph path (per DESIGN.md) runs `SELECT source, destination, classification FROM seen_triples WHERE account_id = ?`. The primary key is `(account_id, source, destination, classification)`, so Postgres can satisfy this query with a leading-column index scan on the PK — this is actually fine. However, there is no index on `seen_triples(account_id, first_seen_at)` if an ORDER BY first_seen_at is added (DESIGN.md mentions this as a candidate). Document in schema.sql that the PK covers the account_id lookup and add the composite index proactively if ORDER BY first_seen_at is needed.

**SHOULD FIX | application.properties — HikariCP pool size of 8 is not correlated to worker thread count in config**
DESIGN.md correctly explains that pool size should equal worker thread count (2 × CPU cores). On a machine with more than 4 cores the worker pool will exceed 8 and workers will starve waiting for connections, or the pool will be larger than the worker count and connections will be idle. The pool size should either be set dynamically (`2 * Runtime.getRuntime().availableProcessors()`) via an environment variable, or documented explicitly that this property must be updated in lockstep with the worker pool size. Hardcoding 8 in `application.properties` without a comment is a silent misconfiguration risk.

---

## CONSIDER

**CONSIDER | logback-spring.xml vs application.properties — Potential logging config conflict**
`application.properties` has no explicit `logging.*` keys, so no conflict exists today. However, if any `logging.level.*` or `logging.pattern.*` property is added in the future, it will be silently ignored because `logback-spring.xml` takes full control of the Logback context when present. Add a comment in `application.properties` noting that logging config lives exclusively in `logback-spring.xml` to prevent future confusion.

**CONSIDER | schema.sql — `services.updated_at` column is never queried**
`updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` is declared on `services` but DESIGN.md shows no query path that reads it. If it is not used for audit or cache invalidation, it adds write overhead on every UPSERT and misleads future readers into thinking it drives some logic. Either use it (e.g., for optimistic concurrency or audit log) or drop it from the schema.

**CONSIDER | E2ETest.java:241 — Test 6 uses `awaitAlert()` as a synchronization barrier for DB persistence**
The comment "Wait for async processing to persist to seen_triples" relies on the assumption that logging the alert (which `awaitAlert` detects) happens after the DB insert. Per the DESIGN.md flow (step 3 inserts, step 5 logs), this ordering holds today. But it is an implicit ordering assumption — if the alert log is ever moved before the DB commit (e.g., in a refactor), Test 6 will have a race where GET /graph returns empty nodes. Add an explicit comment in the test code calling out this ordering dependency so it is not accidentally broken in future PRs.

**CONSIDER | E2ETest.java — Test names are good but Test 3 and Test 4 could be more precise**
`duplicateEvents_fireAlertExactlyOnce` and `nonSensitiveClassifications_doNotFireAlert` are accurate. Consider appending the mechanism to aid debugging: e.g., `duplicateEvents_fireAlertExactlyOnce_viaDatabaseConstraint` and `nonSensitiveClassifications_doNotFireAlert_withinTwoSeconds`. This is a minor style point but helps future readers understand what level of the stack is under test.

**CONSIDER | docker-compose.yml — No `restart: unless-stopped` on the app service**
If the app crashes on startup (e.g., DB not ready despite healthcheck passing at the TCP level, schema init fails), it exits and is not restarted. Adding `restart: unless-stopped` to the `app` service makes local dev more resilient to transient startup failures.

**CONSIDER | DESIGN.md — Architecture diagram reference is broken**
Line 18: `` `![Architecture](architecture.excalidraw.png)` `` references a file that does not exist in the repo. Either generate and commit the diagram (the project has an Excalidraw MCP available) or change the reference to `(diagram pending)` so readers are not confused by a broken image link.

---

Total: 4 MUST FIX, 6 SHOULD FIX, 5 CONSIDER
