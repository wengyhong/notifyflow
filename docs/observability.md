# Observability

NotifyFlow uses Micrometer and Prometheus for webhook send metrics, and structured
fields in delivery logs to identify individual attempts. PostgreSQL remains the
source of delivery state and attempt history.

## Local management endpoints

Start the application with the `local` profile, using your existing database,
RabbitMQ, and Redis environment settings:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--server.port=7829"
```

The application port in this example is 7829. The separate management listener
binds to `127.0.0.1:9091`, as configured in `application-local.properties`.

- Health: http://127.0.0.1:9091/actuator/health
- Prometheus: http://127.0.0.1:9091/actuator/prometheus

The local security chain permits anonymous GET requests to health and Prometheus,
uses no HTTP session, and denies other management requests. Application session
authentication, CSRF protection, and producer API key authentication remain in
place. These anonymous management access settings apply only to the local profile.

Check health:

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:9091/actuator/health"
```

## Webhook metrics

| Prometheus metric | Meaning |
| --- | --- |
| `notifyflow_webhook_attempts_total` | Send attempts grouped by outcome. |
| `notifyflow_webhook_duration_seconds_count` | Number of timed sends. |
| `notifyflow_webhook_duration_seconds_sum` | Total time spent sending, in seconds. |
| `notifyflow_webhook_duration_seconds_max` | Maximum send duration within the timer's recent window. |

The attempt counter has four fixed `outcome` values:

- `success`: an HTTP response from 200 through 299.
- `http_failure`: an HTTP response outside that range, including retryable responses.
- `io_failure`: the sender throws an `IOException`, such as a connection failure or timeout.
- `interrupted`: the sender throws an `InterruptedException`.

The duration timer combines all four outcomes. Elapsed time is measured around
`WebhookHttpSender.send` with `System.nanoTime()`. It excludes claiming work,
rate-limit checks, outcome logging, and saving the result to PostgreSQL.

Each handled send outcome records one counter increment and one duration sample.
Skipped jobs and rate-limit deferrals before sending record neither. Retried and
manually replayed sends count as additional attempts. A send outcome is recorded
before saving the result, so its metric remains recorded if that database write
fails; a successful send metric does not prove the delivery state was committed.

Metrics are in memory and reset when the application restarts. The maximum is a
recent-window value, not a lifetime maximum. No delivery IDs, endpoint IDs, URLs,
or secrets are used as metric labels.

Read the custom metric series:

```powershell
(Invoke-WebRequest `
    -Uri "http://127.0.0.1:9091/actuator/prometheus" `
    -UseBasicParsing).Content -split "`n" |
    Select-String '^notifyflow_webhook_(attempts_total|duration_seconds)'
```

After one completed send, the corresponding outcome counter and duration count
increase by one. The duration sum increases by the measured send time in seconds.
The series can initially be zero before any sends occur.

## Delivery logs

`DeliveryProcessingService` uses a shared `deliveryLog` helper to attach
`deliveryId` and `attemptId` to each send log event.

| Message | Level | Additional field |
| --- | --- | --- |
| `Starting webhook send` | INFO | None |
| `Webhook HTTP response received` | INFO | `responseStatus` |
| `Webhook send failed` | WARN | `errorType` |
| `Webhook send interrupted` | WARN | None |

The response log describes the HTTP response before the database update. It does
not assert that the outcome was saved. Interruption handling preserves the thread
interrupt flag and rethrows the original exception.

Local console output displays the structured fields through this setting:

```properties
logging.pattern.level=%5p %kvp
```

`%5p` renders the level; `%kvp` renders the fields attached with `addKeyValue`.
These are structured fields displayed in text logs; JSON output is not configured.
Restart the application after changing logging properties.

Search the application terminal for a `deliveryId` to follow that delivery. Retries
and manual replay keep the delivery ID and create a different attempt ID for each
new send attempt. The receiver terminal prints received requests separately.

If you have saved console output to a file, PowerShell can filter it:

```powershell
Select-String -Path .\notifyflow.log -SimpleMatch "<delivery-id>"
```

Replace the placeholder with the actual UUID. NotifyFlow does not currently
configure a log file; this command assumes you saved one yourself.

The service's send logs exclude payloads, signing secrets, destination URLs, raw
exception messages, and attached exceptions. I/O failures log the exception class
name only. This describes these send logs, not every log emitted by dependencies.

## Verified locally

On 2026-11-22, the guided smoke checks verified:

- A successful delivery incremented the success counter and duration metrics.
- Start and response logs displayed matching delivery and attempt IDs with HTTP 204.
- Stopping the receiver produced a warning with the attempt IDs and error type.
- After exhausting retries, manual replay with the receiver available delivered
  successfully, retaining the delivery ID and creating a new attempt ID.

The standalone receiver used for these checks responds with HTTP 204 without
verifying signatures. See [webhook security](webhook-security.md) for signature
verification and [manual replay](manual-replay.md) for replay behavior.

## Automated checks

Run the focused metrics, logging, management security, processing, and replay tests:

```powershell
.\mvnw.cmd "-Dtest=DeliveryMetricsTests,DeliveryProcessingLoggingTests,DeliveryProcessingServiceIntegrationTests,DeliveryReplayServiceIntegrationTests,LocalManagementSecurityConfigurationTests,LocalManagementHttpIntegrationTests" test
```

Metrics tests check counts, duration totals, maximum duration, and validation without
partial updates. Logging tests inspect actual log events and their fields, including
the exclusion of sensitive data. Management tests verify Prometheus export and
access restrictions. Integration tests use isolated test containers.
