# Flow Security — Event Ingestion Service

Detects sensitive data flows between services and fires alerts.
Built with Java 21, Spring Boot MVC, PostgreSQL.

---

## What It Does

Receives events from a sensor. Each event describes data flowing between two services.
When a sensitive data type (FIRST_NAME, LAST_NAME, CREDIT_CARD_NUMBER, SOCIAL_SECURITY_NUMBER)
flows from service A to service B for the first time, the system fires an alert — exactly once.

Alert severity:
- **MEDIUM** — new sensitive flow, neither service is public
- **HIGH** — new sensitive flow, one of the services is marked public

Alerts appear as structured JSON log lines. No HTTP endpoint for alerts — the log is the delivery.

---

## Run With Docker

```bash
./gradlew bootJar
docker-compose up --build
```

App starts on port 8080. PostgreSQL starts on 5432.
Schema is loaded automatically on startup.

---

## API

### Ingest events (sensor)
```
POST /events
X-Account-ID: <account-id>
Content-Type: application/json

[
  {
    "date": "1610293274000",
    "source": "users",
    "destination": "payment",
    "values": {
      "firstName": "FIRST_NAME",
      "price": "NUMBER"
    }
  }
]
```
Returns `202 Accepted` immediately. Processing is async.

---

### Mark service as public/private (ops)
```
PUT /services/{serviceName}?public=true
X-Account-ID: <account-id>
```

---

### Get service graph (vis.js compatible)
```
GET /graph
X-Account-ID: <account-id>
```
Returns:
```json
{
  "nodes": [{ "id": "users", "label": "users" }],
  "edges": [{ "from": "users", "to": "payment", "label": "CREDIT_CARD_NUMBER,FIRST_NAME" }]
}
```
Edge `label` is the sorted comma-separated list of classifications observed on that flow.

---

## Alert Log Format

One JSON line per alert, written to stdout:
```json
{
  "type": "SECURITY_ALERT",
  "account_id": "acct-1",
  "source": "users",
  "destination": "payment",
  "classification": "FIRST_NAME",
  "severity": "MEDIUM",
  "detected_at": "2026-03-10T10:00:00Z"
}
```

---

## Sensitive Classifications

| Classification | Sensitive |
|---|---|
| FIRST_NAME | yes |
| LAST_NAME | yes |
| CREDIT_CARD_NUMBER | yes |
| SOCIAL_SECURITY_NUMBER | yes |
| DATE | no |
| NUMBER | no |

---

## Run Tests

```bash
./gradlew test
```

Requires Docker (Testcontainers spins up PostgreSQL automatically).

---

## Schema

Two tables. Schema loads on startup — no migration tool.

```sql
services      (account_id, service_name) PK → is_public, updated_at
seen_triples  (account_id, source, destination, classification) PK → first_seen_at
```

The `seen_triples` composite PK is the exactly-once guarantee.
`INSERT ON CONFLICT DO NOTHING` is the atomic deduplication guard.

---

## Design Notes

See [DESIGN.md](DESIGN.md) for full architecture decisions and trade-offs.

---

## Decisions

### PR2: Domain model, service repository, PUT /services API
- **ServiceCache uses ConcurrentHashMap (not Caffeine):** Caffeine eviction would silently miss-as-private — incorrect. Services table is small and bounded; an unbounded map is safer here.
- **Write-through cache with putIfAbsent in warmup:** Warmup uses `putIfAbsent` to avoid overwriting a concurrent `PUT /services` write that raced with the DB snapshot read.

### PR3: Async ingestion pipeline
- **ApplicationRunner for worker startup:** Guarantees `ServiceCacheWarmup` completes before first event is processed — no race on services cache at boot.
- **503 on queue full (offer=false):** Tomcat thread never blocks on processing; sensor handles backpressure via retry.

### PR4: Alert engine — exactly-once sensitive data flow detection
- **DB constraint is the exactly-once guarantee, not the cache:** `INSERT ON CONFLICT DO NOTHING` returns 0 or 1 atomically — only the thread that gets 1 fires the alert. Cache is a fast-path optimisation, not the authority.
- **`markSeen` called unconditionally after DB decision (before alert):** Both the conflict and inserted branches warm the cache at the same point, eliminating asymmetry. Timestamp is stamped at detection entry via injected `Clock`, not after DB+severity latency.

### PR5: GET /graph — vis.js service data-flow graph
- **Grouping in Java, not SQL:** One row per (source, destination, classification) triple; classifications aggregated into `EnumSet` in Java. Avoids `array_agg` dialect differences and keeps SQL readable.
- **`LinkedHashSet` for node order, sorted label for edge label:** Encounter-order nodes and alphabetically-sorted classification labels make the response body deterministic and diff-friendly.
