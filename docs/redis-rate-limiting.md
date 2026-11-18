# Redis delivery rate limiting

NotifyFlow uses Redis to coordinate delivery permits per webhook endpoint.
PostgreSQL remains authoritative for events, delivery state, attempt history,
and retry schedules. Redis stores short-lived counters, not delivery jobs.

## Current policy

`RedisDeliveryRateLimiter` permits five acquisitions per endpoint in a
1,000-millisecond fixed window. These values are currently hardcoded arguments
to the Lua script, not application properties.

The key is `notifyflow:delivery:rate-limit:<endpoint-id>`. Different endpoints
have independent counters. Workers sharing the same Redis instance and key
namespace share the same allowance.

The first acquisition creates the counter with a one-second expiry. Further
permitted acquisitions increment it without extending the expiry. Once the
counter reaches five, the script returns the remaining time to live rather
than consuming another permit. If an exhausted counter has no expiry, the
script repairs its expiry. Denied decisions always have a positive delay.

Redis executes the Lua script without interleaving commands from other clients,
so concurrent workers cannot independently read and increment the same counter
past the allowance. A return value of zero means allowed; a positive value is
the wait in milliseconds.

This is a fixed-window admission limit. Bursts can occur near window boundaries;
it does not guarantee five HTTP requests in every rolling second or evenly
space requests. The permit is acquired before the HTTP request starts.

## Delivery flow

```text
RabbitMQ delivery ID
    -> DeliveryJobListener
    -> DeliveryProcessingService.claim
    -> lock delivery row in PostgreSQL
    -> check pending/due-retry eligibility
    -> request a Redis permit for the endpoint
         allowed -> save an attempt and commit
                    -> send HTTP outside the database transaction
                    -> record the outcome in another transaction
         denied  -> save RETRY_SCHEDULED and next_attempt_at, then commit
                    -> return without creating an attempt or sending HTTP
```

The denied path reads the clock after the limiter returns, then adds the retry
delay. Time spent waiting for Redis therefore does not turn the new schedule
into an already-expired one.

`DeliveryRetryScheduler.publishDueRetries()` checks PostgreSQL for
`RETRY_SCHEDULED` rows with `next_attempt_at <= now`. Each run selects up to
100 IDs and publishes them to RabbitMQ through `DeliveryJobPublisher`.
`DeliveryJobListener` receives each ID and invokes processing again, including
a fresh rate-limit check.

`@EnableScheduling` enables this polling. The default fixed delay is 1,000 ms
after a scheduler execution finishes; it can be overridden with
`notifyflow.delivery.retry-poll-delay`. A due time is an earliest eligibility
time, not a guarantee of immediate processing.

Rate-limit deferrals preserve existing attempt history and the retry cycle.
They do not consume or reset the five-attempt HTTP retry budget, including
after a manual replay. Duplicate jobs for deliveries that are already processing,
finished, or not yet due do not acquire another permit.

## Redis failures and recovery

The limiter denies permission with a one-second retry delay when Redis raises
a Spring `DataAccessException`, or when the script result is null or negative.
This includes connection, timeout, and script execution failures handled by
the adapter. The worker persists the deferral without sending HTTP.

While Redis stays unavailable, due deliveries are republished and deferred
again. PostgreSQL retains them throughout the outage. After Redis becomes
available, a later scheduled job can acquire a permit and send the webhook;
the event does not need to be submitted again.

The Redis call happens while the PostgreSQL delivery row is locked. A slow
Redis call therefore holds that lock and occupies a worker until it returns
or fails. Redis connection and command timeouts are configured below. The
Redis operation and PostgreSQL transaction are separate: if a permitted claim
later rolls back, the consumed permit is not refunded.

Counters are temporary. Expiry, eviction, or loss of Redis data can start a
fresh allowance. Durable recovery depends on PostgreSQL delivery rows, not on
preserving these counters. Rate limiting does not add exactly-once HTTP delivery.

## Local configuration

The current local Redis container can be started with:

```powershell
docker start notifyflow-redis
docker exec notifyflow-redis redis-cli PING
```

Expect `PONG`. If the container does not exist, create it once:

```powershell
docker run -d --name notifyflow-redis -p 127.0.0.1:6379:6379 redis:8.2.11-alpine
```

The local container uses a loopback-only published port. Current application
properties are:

```properties
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
spring.data.redis.connect-timeout=2s
spring.data.redis.timeout=2s
spring.data.redis.repositories.enabled=false
```

The application uses `StringRedisTemplate` and the script bean backed by
`redis/delivery-rate-limit.lua`; it does not need Spring Data Redis repositories.

## Manual verification

Keep PostgreSQL, RabbitMQ, Redis, NotifyFlow, and the standalone signed receiver
running. Start NotifyFlow with the `local` profile so loopback receiver URLs are
allowed. See [webhook security](webhook-security.md) for receiver setup and
signature verification.

In a PowerShell terminal, set the actual application port and the ID of a
dedicated endpoint pointing at the receiver:

```powershell
$baseUrl = "http://localhost:14184"
$endpointId = "1990fcb2-3db0-4664-b949-3892111ba544"
```

Those values identify the November 18, 2026 demo; replace them for another run.
Subscribe the endpoint to `ratelimit.demo` once:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/subscriptions/$endpointId" `
  -ContentType "application/json" `
  -Body '{"eventType":"ratelimit.demo"}'
```

Send a burst with a separate idempotency key for each event:

```powershell
$accepted = @(
    1..20 | ForEach-Object {
        Invoke-RestMethod `
          -Method Post `
          -Uri "$baseUrl/api/events" `
          -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } `
          -ContentType "application/json" `
          -Body (@{
              type = "ratelimit.demo"
              payload = @{ number = $_ }
          } | ConvertTo-Json)
    }
)
$accepted.Count
```

Check the results after processing finishes:

```powershell
docker exec notifyflow-postgres psql -U notifyflow -d notifyflow -c "SELECT d.status, COUNT(DISTINCT d.id) AS deliveries, COUNT(a.id) AS http_attempts FROM deliveries d LEFT JOIN delivery_attempts a ON a.delivery_id = d.id WHERE d.endpoint_id = '$endpointId' GROUP BY d.status;"
```

For a fresh demo endpoint whose receiver returns 204, expect 20 delivered rows
and 20 attempts. This aggregate verifies completion and absence of extra HTTP
attempts; it does not by itself prove the timing of rate-limit admissions.
Exact counter and concurrency behavior is covered by the Redis integration tests.

To test failure and recovery, stop Redis and submit another event:

```powershell
docker stop notifyflow-redis

$outageEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/events" `
  -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } `
  -ContentType "application/json" `
  -Body '{"type":"ratelimit.demo","payload":{"phase":"redis-down"}}'

docker exec notifyflow-postgres psql -U notifyflow -d notifyflow -c "SELECT d.id, d.status, d.next_attempt_at, COUNT(a.id) AS http_attempts FROM deliveries d LEFT JOIN delivery_attempts a ON a.delivery_id = d.id WHERE d.event_id = '$($outageEvent.id)' GROUP BY d.id;"
```

After the worker handles the job, expect `RETRY_SCHEDULED`, a populated retry
time, and zero attempts. A newly accepted job can briefly remain `PENDING`.

Restart Redis, wait for the receiver to print `{"phase":"redis-down"}`, and
rerun the same query:

```powershell
docker start notifyflow-redis

docker exec notifyflow-postgres psql -U notifyflow -d notifyflow -c "SELECT d.id, d.status, d.next_attempt_at, COUNT(a.id) AS http_attempts FROM deliveries d LEFT JOIN delivery_attempts a ON a.delivery_id = d.id WHERE d.event_id = '$($outageEvent.id)' GROUP BY d.id;"
```

With a successful receiver, expect `DELIVERED`, a null retry time, and one attempt.

### Observed results: November 18, 2026

| Scenario | Status | Deliveries | HTTP attempts | Retry time |
| --- | --- | --- | --- | --- |
| Twenty accepted burst events, after completion | DELIVERED | 20 | 20 | Cleared |
| New event while Redis was stopped | RETRY_SCHEDULED | 1 | 0 | Populated |
| Same event after Redis restarted | DELIVERED | 1 | 1 | Cleared |

The receiver verified the recovered webhook without resubmitting the event.

## Automated verification

Run the affected tests with Docker available:

```powershell
.\mvnw.cmd "-Dtest=DeliveryProcessingServiceIntegrationTests,DeliveryReplayServiceIntegrationTests,DeliveryRateLimitDecisionTests,RedisDeliveryRateLimiterTests,RedisDeliveryRateLimiterIntegrationTests" test
```

This selection passed 109 tests on November 18, 2026. Coverage includes decision
validation, Redis failure fallback, window expiry and endpoint isolation,
concurrent acquisitions through independent Redis clients, deferral without an
HTTP attempt, due-time handling, preserved retry budgets and replay cycles,
the clock reading after the Redis call, and rollback on an unexpected limiter
failure. Integration tests use isolated PostgreSQL and Redis containers.
