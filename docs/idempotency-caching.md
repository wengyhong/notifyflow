# Event idempotency caching

NotifyFlow caches accepted event snapshots in Redis so repeat ingestion requests
can be checked without querying PostgreSQL. PostgreSQL retains the durable
idempotency key and enforces uniqueness within its tenant. Cache expiry, deletion,
or an outage does not make an existing key available for another event in that tenant.

## Request rules

Both `POST /api/events` (admin session) and `POST /api/producer/events` (producer
key) require an `Idempotency-Key` header. Authentication determines the permitted
tenant before ingestion. Both cached and database lookups apply the following
comparison within that tenant:

| Request | Result |
| --- | --- |
| Same key, same event type and JSON payload | Return the original event ID, type, and creation time with HTTP 202 |
| Same key, different type or payload | HTTP 409 via `IdempotencyConflictException` |
| Different key, same content | A separate event can be created |
| Different tenant, same key | An independent event can be created |

Payloads are compared as `JsonNode` trees, not raw JSON strings. Object property
ordering and whitespace differences do not by themselves cause a conflict.
A conflicting request does not replace the stored event or create deliveries.
To submit a distinct event, use a new idempotency key.

## Cached snapshot

`EventSnapshot` contains `id`, `eventType`, `payload`, and `createdAt`.
The record's compact constructor copies the incoming payload, and its accessor
returns a defensive copy. This protects the internal JSON tree from mutation
through the entity or a caller's reference. `from(Event)` performs the mapping.

The payload is necessary to detect conflicts on a cache hit. It is not included
in `EventAcceptedResponse`, whose HTTP fields remain `id`, `type`, and `createdAt`.

| Setting | Current behavior |
| --- | --- |
| Key | `notifyflow:event:idempotency:<tenant-id>:<idempotency-key>` |
| Value | Event snapshot serialized as JSON |
| TTL | 24 hours, currently hardcoded in `put()` |
| Cache hit | Does not rewrite the entry or extend its TTL |
| Successful database path | Requests a cache write with a fresh TTL |
| Missing keys or failed ingestion | Not cached as successful events |

`EventIdempotencyCache` requires a tenant ID for both lookup and write operations.
`RedisEventIdempotencyCache` implements them with `StringRedisTemplate` and
`JsonMapper`. Configuration is shared with the other Redis features; see
[Redis delivery rate limiting](redis-rate-limiting.md#local-configuration).

## Ingestion flow

```text
Authenticated session or producer event request + Idempotency-Key
    -> tenant-specific shared ingestion logic
         Redis hit -> compare cached type and payload
                      -> match: return original EventSnapshot
                      -> mismatch: HTTP 409
         Redis miss/unavailable -> find event in PostgreSQL by tenant and key
              existing -> compare type and payload
                          -> match: reuse event
                          -> mismatch: HTTP 409
              absent -> transaction creates event, matching deliveries,
                        and their delivery outbox requests
              successful result -> create EventSnapshot
                                -> publish cache-write request
                                -> return snapshot
```

Cache hits skip database access, delivery creation, and cache-write publication.
A database lookup that finds an existing event repopulates the cache after
validating the content. Initial creation and reuse therefore produce the same
acceptance response shape.

Two requests can both miss Redis and PostgreSQL before either inserts.
The `uq_events_tenant_idempotency_key` database constraint resolves this race. In the
normal ingestion path, the losing creation transaction rolls back, and recovery
loads the winning event and applies the same content comparison. Unrelated
integrity violations are rethrown. Redis is not a distributed lock or the
authority deciding whether duplicate insertion is allowed.

## Commit-safe cache writes

After a successful database path, ingestion publishes an internal
`EventIdempotencyCacheRequestedEvent(tenantId, idempotencyKey, snapshot)`.
`EventIdempotencyCacheRequestedEventListener` handles it with:

```java
@TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true)
```

If ingestion joined an enclosing transaction, the listener waits for that
transaction to commit. Rollback or a before-commit outbox failure prevents the
cache write. Returning from `ingest()` alone does not imply an enclosing
transaction has committed.

If there is no active transaction, `fallbackExecution = true` runs the listener
immediately. This covers the normal path where `TransactionTemplate.execute()`
has already committed creation before ingestion publishes the cache request,
and reuse of an existing database event outside an enclosing transaction.

The event is local to Spring; it is not a RabbitMQ message. Cache publication is
best effort, not a durable outbox operation. A crash after database commit but
before the cache write leaves a cache miss that PostgreSQL can resolve later.
The durable delivery outbox remains a separate mechanism.

## Failures and limits

Redis `DataAccessException` and JSON deserialization failures become cache
misses. Redis writes and JSON serialization failures are tolerated so a cache
failure does not discard a successful database result. PostgreSQL failures
still propagate; they are not treated as successful ingestion.

During a Redis outage, matching requests still reuse the original event through
PostgreSQL, and conflicting requests still return HTTP 409. Redis timeouts can
add latency. Once Redis reconnects, a successful database lookup can repopulate
the snapshot.

The application currently treats accepted events as unchanged data. There is no
cache eviction operation for editing or deleting events. The 24-hour cache TTL
does not define database retention or the lifetime of the idempotency guarantee;
that guarantee depends on retaining the PostgreSQL row and its unique key.
The cache stores the full payload under tenant-scoped keys. Old unscoped entries
are not read by the tenant-aware lookup and expire naturally. Event retention
still needs to account for the lifetime of the database idempotency guarantee.

This cache avoids duplicate event creation for a reused key. It does not add
exactly-once HTTP delivery or deduplicate separate requests using different keys.

## Manual verification

The walkthrough below records the original pre-authentication Milestone 7 check.
For current requests, follow [authentication and producer verification](authentication.md#local-verification-with-powershell).
Session calls to `/api/events` additionally need the session, CSRF token, and
`X-Tenant-ID`. Producer calls use `/api/producer/events` with a Bearer key. Inspect
Redis using `notifyflow:event:idempotency:<tenant-id>:<idempotency-key>`; the old
unscoped inspection commands below apply only to that historical checkpoint.

Run PostgreSQL, RabbitMQ, Redis, and NotifyFlow. In another PowerShell terminal,
replace the application port if necessary, then create a fresh demo key:

```powershell
$baseUrl = "http://localhost:8127"
$idempotencyKey = "cache-demo-$([guid]::NewGuid())"
$body = @{
    type = "idempotency.cache.demo"
    payload = @{ orderId = 128 }
} | ConvertTo-Json

$firstEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/events" `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -ContentType "application/json" `
  -Body $body

$firstEvent
docker exec notifyflow-redis redis-cli GET "notifyflow:event:idempotency:$idempotencyKey"
docker exec notifyflow-redis redis-cli TTL "notifyflow:event:idempotency:$idempotencyKey"
```

Expect a snapshot with the same event ID and payload, and a positive TTL no
greater than 86,400 seconds. No matching subscription is needed to test event
idempotency. Keep this terminal open to retain the variables.

### Matching retry

```powershell
$repeatedEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/events" `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -ContentType "application/json" `
  -Body $body

$repeatedEvent.id -eq $firstEvent.id
```

Expect `True`. The automated tests separately verify that this cache-hit path
skips the database; response equality alone does not prove which store was read.

### Conflicting retry

```powershell
$changedBody = @{
    type = "idempotency.cache.demo"
    payload = @{ orderId = 999 }
} | ConvertTo-Json

try {
    Invoke-RestMethod `
      -Method Post `
      -Uri "$baseUrl/api/events" `
      -Headers @{ "Idempotency-Key" = $idempotencyKey } `
      -ContentType "application/json" `
      -Body $changedBody
} catch {
    [int]$_.Exception.Response.StatusCode
}
```

Expect `409`. The original payload remains `{"orderId":128}`.

### Cache miss and refill

Delete only this demo entry to simulate Redis forgetting the key:

```powershell
docker exec notifyflow-redis redis-cli DEL "notifyflow:event:idempotency:$idempotencyKey"

$fallbackEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/events" `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -ContentType "application/json" `
  -Body $body

$fallbackEvent.id -eq $firstEvent.id
docker exec notifyflow-redis redis-cli GET "notifyflow:event:idempotency:$idempotencyKey"
```

Expect `True` and a restored snapshot with the original ID and payload.

### Redis outage and recovery

```powershell
docker stop notifyflow-redis

$outageEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/events" `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -ContentType "application/json" `
  -Body $body

$outageEvent.id -eq $firstEvent.id
```

Expect `True`. While Redis remains stopped, repeat the conflicting retry block
above; expect `409` again. Then restore Redis and retry the original request:

```powershell
docker start notifyflow-redis

Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/events" `
  -Headers @{ "Idempotency-Key" = $idempotencyKey } `
  -ContentType "application/json" `
  -Body $body

docker exec notifyflow-redis redis-cli GET "notifyflow:event:idempotency:$idempotencyKey"
```

Expect the original event ID and its cached snapshot. If Redis is still
reconnecting, repeat the original request and Redis GET commands.

Finally, check the durable event count:

```powershell
docker exec notifyflow-postgres psql -U notifyflow -d notifyflow -c "SELECT COUNT(*) AS stored_events FROM events WHERE idempotency_key = '$idempotencyKey';"
```

Expect `1`. The generated demo key contains only the fixed prefix and a UUID.

### Observed results: November 18, 2026

The initial event ID was `ffa35184-3146-4a15-a7c7-6567ed8fc270`.

| Check | Observed result |
| --- | --- |
| Initial request and Redis inspection | Original ID, type, payload, and creation time cached |
| TTL check | 86,343 seconds remaining |
| Matching retry with cache present | Original event ID returned |
| Changed payload with cache present | HTTP 409 |
| Delete cache entry and retry | Original ID returned; snapshot repopulated |
| Matching retry while Redis stopped | Original ID returned |
| Changed payload while Redis stopped | HTTP 409 |
| Redis restart and matching retry | Original response and cached snapshot available |
| PostgreSQL count after all retries | One stored event |

## Automated verification

The affected selection passed 74 tests on November 18, 2026. Coverage includes
snapshot copying and JSON round trips; cache hits and type/payload conflicts;
Redis TTL, expiry, key isolation, malformed data, and failure fallback; unchanged
HTTP response mapping; database reuse without extra deliveries; concurrent
identical and conflicting cold requests; and cache writes after commit with
rollback and before-commit failure protection. The subsequent full build also
passed.

Run the affected tests with Docker available:

```powershell
.\mvnw.cmd "-Dtest=EventSnapshotTests,EventIngestionServiceTests,EventIngestionServiceIntegrationTests,EventControllerTests,RedisEventIdempotencyCacheTests,RedisEventIdempotencyCacheIntegrationTests" test
```

Integration tests use isolated PostgreSQL and Redis containers. Run the full
build with `.\mvnw.cmd verify`.
