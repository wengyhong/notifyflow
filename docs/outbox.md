# Transactional delivery outbox

The outbox preserves delivery publishing requests when RabbitMQ is unavailable
or the application stops between committing business data and publishing a job.
PostgreSQL remains the authoritative store for delivery state and pending
publication requests.

## Transactional write

Event ingestion and manual replay publish an internal `DeliveryRequestedEvent`
inside their database transaction. `DeliveryRequestedEventListener` handles it
at `BEFORE_COMMIT` and saves a `DeliveryOutboxMessage`.

The delivery changes and the outbox row commit together. A rollback or failure
to save the outbox request rolls back the business changes as well. These
transactions do not contact RabbitMQ.

The `delivery_outbox` table stores:

| Column | Purpose |
| --- | --- |
| `id` | Unique publishing request ID |
| `delivery_id` | Delivery ID to send as the RabbitMQ message body |
| `created_at` | Time the request was recorded |
| `published_at` | Confirmed publication time; `NULL` means pending |

Multiple outbox rows can reference the same delivery because manual replay
creates another publishing request while preserving the delivery ID.

## Publication

`DeliveryOutboxScheduler` periodically invokes `DeliveryOutboxRelay.publishNext()`.
Each relay call opens its own transaction and:

1. Selects one unpublished row, ordered by `created_at` and `id`.
2. Locks it with `FOR UPDATE SKIP LOCKED`.
3. Publishes its delivery ID through `DeliveryJobPublisher`.
4. Waits for a positive publisher confirmation and checks for a returned message.
5. Records `published_at` and commits.

The row lock remains held during publication and confirmation. Other relays skip
that row and can publish a different available request. JPA dirty checking
persists the publication timestamp at commit.

Broker confirmation establishes that RabbitMQ accepted the publication.
Mandatory routing and publisher returns detect publications that could not be
routed to a queue. Confirmation does not mean the HTTP webhook has succeeded;
the delivery worker records that outcome separately.

## Failure and recovery

- Connection failures, negative confirmations, returned messages, and
  confirmation timeouts fail publication and leave the outbox row unpublished.
- A failure stops the current scheduler run. A later run can retry pending rows.
- If RabbitMQ accepts a message but the database transaction fails before
  recording publication, the row remains pending and may be published again.

Publication therefore has at-least-once semantics. Consumers must tolerate
duplicate jobs. Delivery row locks and status checks prevent simultaneous claims
and skip jobs for deliveries that are already processing or terminal.

Scheduled HTTP retries remain represented by `RETRY_SCHEDULED` deliveries and
their `next_attempt_at` timestamps. `DeliveryRetryScheduler` republishes due
delivery IDs through the same confirmed publisher. The outbox records requests
from initial delivery creation and manual replay.

## Configuration

```properties
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.publisher-returns=true
spring.rabbitmq.template.mandatory=true
```

| Setting | Default |
| --- | --- |
| `notifyflow.delivery.outbox-poll-delay` | 1000 milliseconds after each scheduler run finishes |
| Maximum successful publications per run | 100 |
| Publisher confirmation wait | 5 seconds |

The confirmation wait starts after the send call. Connection establishment has
its own client timeout. The batch limit and confirmation wait are currently
defined in code.

## Automated verification

The tests cover entity validation, database constraints, pending-row ordering,
concurrent publishers, rollback, broker failures, and duplicate publication after
a failed database commit. Ingestion and replay tests verify atomic outbox writes
and protect against duplicate requests.

With Docker running, run the focused tests from PowerShell:

```powershell
.\mvnw.cmd "-Dtest=DeliveryOutbox*Tests,DeliveryJobPublisher*Tests,EventIngestionServiceIntegrationTests,DeliveryReplayServiceIntegrationTests" test
```

## Manual broker-outage verification

Prerequisites: NotifyFlow and PostgreSQL are running, and an enabled endpoint is
subscribed to `outbox.demo`. Its HTTP receiver should return `204`. Use the
application's actual HTTP port for `$baseUrl`.

1. Stop the local broker:

   ```powershell
   docker stop notifyflow-rabbitmq
   ```

2. Submit a fresh event while the broker is stopped:

   ```powershell
   $baseUrl = "http://localhost:8080"
   $body = @{
       type = "outbox.demo"
       payload = @{
           message = "Saved while RabbitMQ was unavailable"
       }
   } | ConvertTo-Json -Depth 5

   $outboxEvent = Invoke-RestMethod `
       -Method Post `
       -Uri "$baseUrl/api/events" `
       -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString() } `
       -ContentType "application/json" `
       -Body $body
   ```

3. Verify the delivery and unpublished outbox row exist:

   ```powershell
   $sql = @"
   SELECT d.id, d.status, o.id AS outbox_id, o.published_at
   FROM deliveries d
   JOIN delivery_outbox o ON o.delivery_id = d.id
   WHERE d.event_id = '$($outboxEvent.id)';
   "@

   docker exec notifyflow-postgres psql -U notifyflow -d notifyflow -c $sql
   ```

   Expect `PENDING` and an empty `published_at`. No webhook should have arrived.

4. Restart RabbitMQ without resubmitting the event:

   ```powershell
   docker start notifyflow-rabbitmq
   ```

5. After the receiver gets the webhook, verify the result:

   ```powershell
   $sql = @"
   SELECT d.id, d.status, o.published_at,
          a.attempt_number, a.response_status
   FROM deliveries d
   JOIN delivery_outbox o ON o.delivery_id = d.id
   LEFT JOIN delivery_attempts a ON a.delivery_id = d.id
   WHERE d.event_id = '$($outboxEvent.id)'
   ORDER BY d.id, a.attempt_number;
   "@

   docker exec notifyflow-postgres psql -U notifyflow -d notifyflow -c $sql
   ```

   Expect `DELIVERED`, a populated `published_at`, and attempt 1 returning `204`.

This recovery sequence was verified locally: event ingestion succeeded during
the outage, PostgreSQL retained the pending publication request, and the delivery
completed after RabbitMQ restarted without another event submission.
