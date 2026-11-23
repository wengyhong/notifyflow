# Portfolio demo walkthrough

Use this guide to record a short demonstration or present the project locally.
Screenshots and a recording are planned artifacts, not included media yet.
Use disposable demo data and keep real credentials out of captures.

## Prepare

1. Follow [local development](local-development.md) to start infrastructure,
   backend on 7829 with the local profile, and the frontend.
2. Register or sign in to a demo workspace.
3. Start the receiver below in a separate terminal.
4. Create an enabled endpoint named `Portfolio receiver` with URL
   `http://127.0.0.1:1786/webhook` and subscribe it to `portfolio.order.created`.
5. Create a producer key in the same workspace. Keep the raw value private;
   it is shown only once. Hide secret/key displays before taking screenshots.

## Start a controllable receiver

In PowerShell:

```powershell
jshell --add-modules jdk.httpserver
```

Then paste into JShell:

```java
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

var responseStatus = new AtomicInteger(204);
var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 1786), 0);
receiver.createContext("/webhook", exchange -> {
    try {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = responseStatus.get();
        System.out.println("Received: " + body + " -> HTTP " + status);
        exchange.sendResponseHeaders(status, -1);
    } finally {
        exchange.close();
    }
});
receiver.start();
```

This receiver controls HTTP responses for the walkthrough. It does not verify
signatures. Use the separate [signature verification walkthrough](webhook-security.md)
when demonstrating HMAC verification, tamper rejection, and secret rotation.

## Submit a successful event

In another PowerShell terminal, read the producer key without displaying it:

```powershell
$producerKey = Read-Host "Producer API key" -AsSecureString
$idempotencyKey = [guid]::NewGuid().ToString()
$body = @{
    type = "portfolio.order.created"
    payload = @{ orderId = 128 }
} | ConvertTo-Json -Depth 5

$accepted = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:7829/api/producer/events" `
    -ContentType "application/json" `
    -Headers @{
        Authorization = "Bearer " + [System.Net.NetworkCredential]::new("", $producerKey).Password
        "Idempotency-Key" = $idempotencyKey
    } `
    -Body $body
$accepted
```

Show the HTTP 204 in the receiver, refresh delivery history, and open the attempt
details. Explain that the API accepts work before background HTTP delivery completes.

To demonstrate idempotency, repeat the same submission using the same body and
`$idempotencyKey`: it returns the same event rather than creating another delivery.
Changing the payload while retaining that key returns HTTP 409. Restore the
original body or use a fresh key for a new event.

## Show automatic recovery

In JShell, make the receiver temporarily unavailable at the HTTP level:

```java
responseStatus.set(503);
```

Set `$idempotencyKey = [guid]::NewGuid().ToString()` in PowerShell and repeat the
submission. Refresh the dashboard to show the 503 attempt and scheduled retry.
Before the retry budget is exhausted, switch JShell back to:

```java
responseStatus.set(204);
```

The next scheduled attempt should succeed. Show the same delivery with multiple
attempts, rather than claiming a new event was created for the retry.

## Show manual replay

Set `responseStatus.set(400);` in JShell, submit another event with a fresh key,
and refresh until the delivery is `FAILED`. HTTP 400 is not automatically retried.

Switch to `responseStatus.set(204);`, then use the dashboard's Replay action.
Refresh the history and attempt details to show the failed and successful attempts.
The delivery ID stays the same and the new attempt gets a different attempt ID.

## Screenshot and recording plan

Capture these after completing the walkthrough; do not add broken placeholder
image links to the README:

| Capture | What to show |
| --- | --- |
| Dashboard overview | Selected workspace, endpoint, and delivery statuses; secrets hidden. |
| Attempt history | One delivery with a failure followed by HTTP 204. |
| Subscriptions | The event type linked to the demo endpoint. |
| Observability | Matching delivery/attempt IDs in logs and the custom Prometheus series. |
| CI | Passing Backend and Frontend jobs for the relevant commit. |

A short recording can follow: purpose, architecture, successful send, automatic
retry, manual replay, and CI. Explain receiver deduplication and at-least-once
publication as engineering tradeoffs. Avoid unsupported throughput or availability
claims; no performance benchmark or hosted service-level objective is documented.

## Finish

Revoke the disposable producer key if it is no longer needed and stop JShell with
`/exit`. Stop local processes and infrastructure following the setup guide.
