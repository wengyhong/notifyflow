# Endpoint detail caching

NotifyFlow caches the public details returned by `GET /api/endpoints/{id}` in
Redis. PostgreSQL remains authoritative. A missing or unavailable cache causes
the query to load PostgreSQL instead.

This cache is used for the detail GET route. Endpoint lists, writes, event
routing, and delivery processing continue to use their existing database paths.
Delivery signing secrets are not stored in this cache.

The separate cache for accepted event requests is described in
[event idempotency caching](idempotency-caching.md).

## Cached data and expiry

`WebhookEndpointSnapshot` is an immutable record containing `id`, `name`, `url`,
`enabled`, `createdAt`, and `updatedAt`. Its `from()` factory copies these fields
from the JPA entity. `RedisWebhookEndpointCache` serializes this snapshot as JSON
using `JsonMapper` and stores it through `StringRedisTemplate`.

| Setting | Current behavior |
| --- | --- |
| Key | `notifyflow:endpoint:<endpoint-id>` |
| Value | Public snapshot as JSON |
| TTL | 60 seconds, currently hardcoded in `put()` |
| Reads | Do not refresh the TTL |
| Writes | Replace the value and set a fresh TTL |
| Missing endpoints | Not cached |

Neither the current nor previous signing secret is included. The cache contains
snapshots rather than serialized JPA entities.

## Read flow

```text
GET /api/endpoints/{id}
    -> WebhookEndpointController
    -> WebhookEndpointQueryService.findById
         cache hit  -> return cached snapshot
         cache miss -> load endpoint from PostgreSQL
                       -> convert to WebhookEndpointSnapshot
                       -> attempt to cache it for 60 seconds
                       -> return snapshot
         absent in PostgreSQL -> return empty -> HTTP 404
```

The controller maps the snapshot to `WebhookEndpointResponse`, preserving the
existing HTTP response fields. A hit skips the repository lookup and does not
rewrite the cache.

## Invalidation after commit

`WebhookEndpointService.changeEnabled()` publishes a
`WebhookEndpointChangedEvent` inside its transaction when the enabled value
actually changes. Missing endpoints and unchanged values publish no event.

`WebhookSecretRotationService.rotate()` publishes the same event after changing
the secret. Rotation also changes `updatedAt`, which is part of the public
snapshot, so it requires invalidation even though the secrets are not cached.
Rejected rotations do not reach the publication line.

`WebhookEndpointCacheInvalidationListener` handles the internal Spring event
with `@TransactionalEventListener(phase = AFTER_COMMIT)` and evicts the key.
The event does not pass through RabbitMQ.

```text
Change endpoint inside a PostgreSQL transaction
    -> publish WebhookEndpointChangedEvent(endpointId)
    -> commit succeeds -> listener deletes the Redis key
    -> next GET loads fresh PostgreSQL data and repopulates Redis

Transaction rolls back -> listener does not evict
```

Evicting after commit avoids deleting the key while the write is still
uncommitted, when a reader could immediately refill it from the old database
state. With the default listener settings, an event published without a
transaction does not trigger this listener.

Creation does not need eviction because missing endpoints are not cached.
Future name/URL updates and deletions must also invalidate the corresponding
key.

## Failures and consistency limits

The Redis adapter treats `DataAccessException` and JSON deserialization failures
as cache misses. Redis write or JSON serialization failures do not discard a
successful PostgreSQL lookup. Eviction failures are tolerated; entries still
have a TTL. Database failures propagate rather than being disguised as missing
endpoints.

Unlike the [delivery rate limiter](redis-rate-limiting.md), which defers sending
when Redis is unavailable, this cache falls back to PostgreSQL. Reads can take
longer during an outage while Redis operations fail or time out. Caching resumes
on a later successful read and write after Redis reconnects.

This is a best-effort cache with eventual consistency. Invalidation is not a
durable outbox operation: an application crash after database commit but before
eviction, or a Redis failure during eviction, can leave an old entry until expiry.
An already-running cache-miss reader can also load old data before an update,
then populate Redis after the writer's eviction. That entry can remain for its
own 60-second TTL. After-commit eviction does not eliminate this concurrent
refill race or guarantee that every GET immediately sees the latest state.

Multiple simultaneous misses can also perform duplicate database reads. The
current implementation does not coordinate cache fills. These limitations apply
to cached public detail reads; delivery processing does not obtain its secrets
or endpoint state from this snapshot cache.

## Manual verification

Keep PostgreSQL, RabbitMQ, Redis, and NotifyFlow running. Replace these demo
values with the actual application port and an existing endpoint ID:

```powershell
$baseUrl = "http://localhost:6331"
$endpointId = "1990fcb2-3db0-4664-b949-3892111ba544"
```

### Populate the cache

```powershell
Invoke-RestMethod "$baseUrl/api/endpoints/$endpointId"
docker exec notifyflow-redis redis-cli GET "notifyflow:endpoint:$endpointId"
docker exec notifyflow-redis redis-cli PTTL "notifyflow:endpoint:$endpointId"
```

Expect JSON containing the six public fields and a positive remaining TTL no
greater than 60,000 ms. `PTTL` reports milliseconds; `-2` means the key is absent.

### Update and invalidate

This changes the endpoint's enabled state. Use a demo endpoint and restore its
original state afterward if needed. Run the block together so expiry is less
likely to obscure the invalidation check:

```powershell
$before = Invoke-RestMethod "$baseUrl/api/endpoints/$endpointId"
$changeBody = @{ enabled = (-not $before.enabled) } | ConvertTo-Json

Invoke-RestMethod `
  -Method Patch `
  -Uri "$baseUrl/api/endpoints/$endpointId/enabled" `
  -ContentType "application/json" `
  -Body $changeBody

docker exec notifyflow-redis redis-cli EXISTS "notifyflow:endpoint:$endpointId"
```

Expect `0` after the successful change. Do not GET the endpoint between the
PATCH and the `EXISTS` check, because GET would repopulate the cache.

Then verify the fresh state and cache refill:

```powershell
Invoke-RestMethod "$baseUrl/api/endpoints/$endpointId"
docker exec notifyflow-redis redis-cli GET "notifyflow:endpoint:$endpointId"
```

Both should show the changed enabled value and updated timestamp. `EXISTS`
reports whether a key exists, not whether an endpoint is enabled. Both enabled
and disabled endpoints can be cached. A result of `0` can also be caused by the
60-second expiry, so it alone does not prove invalidation; automated transaction
tests verify the commit/rollback timing.

### Redis outage and recovery

```powershell
docker stop notifyflow-redis
Invoke-RestMethod "$baseUrl/api/endpoints/$endpointId"
```

The endpoint should still be returned from PostgreSQL. Then restart Redis:

```powershell
docker start notifyflow-redis
Invoke-RestMethod "$baseUrl/api/endpoints/$endpointId" | Out-Null
docker exec notifyflow-redis redis-cli EXISTS "notifyflow:endpoint:$endpointId"
```

Expect `1` once Redis reconnects and the key is populated. If the first lookup
happens during reconnection, repeat the GET and `EXISTS` commands.

### Observed results: November 18, 2026

| Check | Observed result |
| --- | --- |
| Detail GET followed by Redis GET | Public JSON snapshot present; no signing secret |
| Disable endpoint, then check key | `EXISTS` returned `0` |
| Next detail GET and Redis GET | Both showed `enabled: false` and the updated timestamp |
| Detail GET while Redis was stopped | Endpoint details still returned |
| Restart Redis, GET, then inspect key | `EXISTS` returned `1` |

## Automated verification

Tests cover Redis JSON round trips, TTL and expiry, eviction, endpoint key
isolation, malformed JSON and Redis failures; cache hits without repository
access; cache misses and missing endpoints; database failure propagation; and
controller response mapping and HTTP 404 behavior.

PostgreSQL integration tests verify that enabled changes and rotations evict
only after commit, rollbacks preserve the cache, and no-op changes, missing
endpoints, rejected rotations, and events without transactions do not evict.
The affected endpoint test selection passed 121 tests on November 18, 2026;
the subsequent full build also passed.

Run the cache and query checks with Docker available:

```powershell
.\mvnw.cmd "-Dtest=WebhookEndpointControllerTests,WebhookEndpointQueryServiceTests,RedisWebhookEndpointCacheTests,RedisWebhookEndpointCacheIntegrationTests,WebhookEndpointCacheInvalidationIntegrationTests" test
```

Run the full build with:

```powershell
.\mvnw.cmd verify
```

Integration tests use isolated Redis and PostgreSQL containers rather than the
local demo containers.
