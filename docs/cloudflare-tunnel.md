# Temporary HTTPS demo with Cloudflare Tunnel

NotifyFlow can be demonstrated publicly while its containers continue running on
your computer. Cloudflare provides the public HTTPS entry point; `cloudflared`
maintains an outbound tunnel to the local Caddy gateway. This is an on-demand
demo, not an always-on cloud deployment.

The [hosted Cloudflare Worker receiver](../demo-receiver/README.md) is now available
for signature-verifying delivery demonstrations without running a receiver tunnel.
The separate receiver tunnel below remains useful for controlling 400/503 responses
when demonstrating retry and replay. Hosting the receiver does not host NotifyFlow.

```text
Browser -> HTTPS :443 -> Cloudflare -> tunnel -> cloudflared
    -> HTTP localhost:8088 -> Caddy :80
        /api/... -> backend:8080
        other paths -> frontend:3000
```

Quick Tunnels require no domain and generate a temporary `trycloudflare.com`
address. The address changes when you create a new tunnel and stops working when
the tunnel process stops. They have no uptime guarantee. See
[Cloudflare's Quick Tunnel documentation](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

## Prerequisites

- Follow [local development](local-development.md#run-the-applications-in-docker)
  to build and run the complete container stack.
- Confirm login and endpoint listing work at http://localhost:8088.
- Keep Docker and the computer running for the duration of the demonstration.
- Use disposable demo data. The public URL exposes the application's login and
  registration routes, while business operations still require authentication
  and tenant access. Do not share credentials, signing secrets, or session values.

## Install the tunnel client on Windows

```powershell
winget install --id Cloudflare.cloudflared --exact
```

Open a new PowerShell terminal and check `cloudflared --version`. If it is not on
PATH, the installed executable can be invoked directly:

```powershell
& "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe" --version
```

The guided walkthrough used cloudflared 2026.11.1. See the
[official downloads](https://developers.cloudflare.com/tunnel/downloads/) for
other installations and updates. Installing the client does not start public access.

## Obtain a temporary address

From the repository root, stop the gateway so the application is not exposed
before its HTTPS-aware configuration is active:

```powershell
docker compose -f compose.yml -f compose.app.yml stop gateway
```

In a dedicated PowerShell terminal, start the tunnel:

```powershell
& "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe" tunnel --url http://localhost:8088
```

Keep that terminal open and copy the generated HTTPS URL. It may return a gateway
error until the next step starts Caddy. Do not commit that temporary URL as a stable
portfolio link.

## Start the HTTPS demo configuration

In a second PowerShell terminal at the repository root, set the actual generated
address, without a trailing slash:

```powershell
$env:NOTIFYFLOW_PUBLIC_ORIGIN = "https://<your-generated-host>.trycloudflare.com"

docker compose -f compose.yml -f compose.app.yml -f compose.tunnel.yml config --quiet
docker compose -f compose.yml -f compose.app.yml -f compose.tunnel.yml up -d
```

Replace the placeholder with the URL printed by your tunnel. Keep the value in
this process environment rather than saving a temporary hostname into `.env`.
Compose still reads the existing database and RabbitMQ passwords from `.env`.

`compose.app.yml` maps this variable to SvelteKit's `ORIGIN`, with a default of
`http://localhost:8088` for ordinary local development. The tunnel override also:

- Enables Spring Boot's framework handling of forwarded headers.
- Sets session cookies to `Secure`, `HttpOnly`, and `SameSite=Lax`.
- Replaces Caddy's regular configuration mount with `Caddyfile.tunnel`.

The tunnel Caddyfile routes requests as before, removes conflicting `Forwarded`,
`X-Forwarded-Prefix`, and `X-Forwarded-Ssl` values, and explicitly sets the upstream
protocol to HTTPS and public port to 443. Caddy supplies `X-Forwarded-Host` from
the request host. These headers describe the original browser connection; they do
not encrypt local traffic or open port 443 on your computer. See
[Caddy's header handling](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers).

This fixed HTTPS assumption is specific to the tunnel override. The backend and
frontend have no direct host port mappings; the gateway remains bound to loopback.
Use the HTTPS demo URL for browser checks while this override is active.

## Verify the demo

1. Log in through the generated HTTPS URL and load endpoints and delivery history.
2. In browser developer tools, inspect Application -> Cookies -> the demo address.
   `JSESSIONID` should have Secure and HttpOnly checked and SameSite set to Lax.
   Do not copy or share its value.
3. Log out, refresh, and confirm the dashboard requires login again. Log back in.
4. Create a disposable endpoint named `Tunnel verification` with URL
   `https://example.com/webhook`. Confirm it appears. Do not add a subscription;
   this checks an authenticated write, not a real webhook receiver.
5. Check both management paths from PowerShell:

   ```powershell
   curl.exe -s -o NUL -w "%{http_code}" "$env:NOTIFYFLOW_PUBLIC_ORIGIN/actuator/health"
   curl.exe -s -o NUL -w "%{http_code}" "$env:NOTIFYFLOW_PUBLIC_ORIGIN/actuator/prometheus"
   ```

   Expect 404 for each. Caddy sends these non-API paths to SvelteKit, rather than
   publishing Spring Boot's management endpoints.

The guided walkthrough on 2026-11-23 confirmed HTTPS login and reads, the cookie
attributes, logout/login, endpoint creation, a reported 404 management-path result,
and restoration of local HTTP login. These checks do not establish production
availability or verify every API over the tunnel.

## Deliver from the backend container to a local receiver

An endpoint URL such as `http://127.0.0.1:1786/webhook` points inside the backend
container when the worker sends it. The container configuration also keeps private
and loopback destination restrictions enabled. For this smoke test, publish a
separate HTTPS tunnel to a receiver on your computer instead of weakening those
restrictions. The dashboard tunnel is not required when submitting events through
the regular local gateway.

In a new PowerShell terminal:

```powershell
jshell --add-modules jdk.httpserver
```

Paste into JShell:

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

Keep JShell open. In another PowerShell terminal, start the receiver tunnel:

```powershell
& "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe" tunnel --url http://127.0.0.1:1786
```

Keep this terminal open too. In the dashboard, create an endpoint named
`Container delivery demo`, using the new receiver tunnel's URL followed by
`/webhook`. Subscribe it to `container.delivery.demo`. Create a producer key in
the same workspace, then submit from another PowerShell terminal:

```powershell
$producerKey = Read-Host "Producer API key" -AsSecureString
$body = @{
    type = "container.delivery.demo"
    payload = @{ orderId = 140 }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8088/api/producer/events" `
    -ContentType "application/json" `
    -Headers @{
        Authorization = "Bearer " + [System.Net.NetworkCredential]::new("", $producerKey).Password
        "Idempotency-Key" = [guid]::NewGuid().ToString()
    } `
    -Body $body
```

This command assumes the regular local HTTP gateway configuration. If the
application is still in HTTPS tunnel mode, submit to its public HTTPS origin
instead, or restore local mode first. The producer route uses its Bearer key and
does not require a browser session or CSRF token.

### Verified container delivery scenarios

The user confirmed all three scenarios on 2026-11-23, using the containerized
backend and the public HTTPS receiver tunnel:

| Scenario | Steps | Observed result |
| --- | --- | --- |
| Successful delivery | Keep `responseStatus` at 204 and submit an event. | Receiver prints the payload; delivery becomes `DELIVERED` with HTTP 204. |
| Automatic retry | Set `responseStatus.set(503);`, submit a new event, then switch to `responseStatus.set(204);` after the first failure. | The same delivery has a 503 attempt followed by a 204 attempt and becomes `DELIVERED`. |
| Manual replay | Set `responseStatus.set(400);`, submit a new event, and wait for `FAILED`. Switch to 204 and use Replay in the dashboard. | The same delivery becomes `DELIVERED` and retains both the 400 and 204 attempts. |

Repeat the `Invoke-RestMethod` command for each new scenario; it generates a fresh
idempotency key. Changing the key intentionally creates a new event even if its
payload matches a previous one. Refresh the dashboard to inspect attempt history.

These checks exercise ingestion, outbox publication, RabbitMQ consumption,
outbound HTTP from Docker, retry scheduling, and replay. This receiver accepts
requests without verifying signatures or deduplicating them, so the results do
not verify HMAC correctness or exactly-once processing. Use disposable payloads;
anyone who knows the receiver URL can call it while its tunnel runs. For signature
verification, see [webhook security](webhook-security.md).

When finished, stop the receiver tunnel with Ctrl+C, exit JShell with `/exit`, and
disable the disposable endpoint or remove its subscription to avoid scheduling
further sends to an expired address. Revoke the demo producer key when no longer
needed. Recreating the receiver tunnel requires creating a new endpoint/subscription
with the new URL and disabling the old endpoint. Screenshots and recordings
are deferred.

## Stop public access and return to local HTTP

First press Ctrl+C in the tunnel terminal. Then, in the terminal that set the
public-origin variable, run:

```powershell
Remove-Item Env:NOTIFYFLOW_PUBLIC_ORIGIN -ErrorAction SilentlyContinue
docker compose -f compose.yml -f compose.app.yml up -d
```

This recreates changed services using the regular Caddyfile, local frontend origin,
and base backend cookie settings. Open http://localhost:8088 and verify login.
The database volume and delivery history are retained. If you put a public-origin
value in `.env`, remove that entry too, because Compose would otherwise reload it.

To stop the local application stack as well:

```powershell
docker compose -f compose.yml -f compose.app.yml stop
```

Stopping the tunnel alone does not stop containers. Closing the log-following
command alone does not stop the tunnel. The computer must remain awake and online
while presenting the public demo.
