# Hosted demo receiver

A TypeScript Cloudflare Worker that verifies NotifyFlow webhook signatures and
acknowledges valid requests with HTTP 204. It runs independently of the local
NotifyFlow containers and replaces the JShell receiver/tunnel for signature demos.

Verified receiver endpoint:

```text
https://demo-receiver.wengyhongwork.workers.dev/webhook
```

This address is a demonstration endpoint, not a public event-ingestion API.
Requests need the signing secret belonging to the configured NotifyFlow endpoint.
The NotifyFlow backend and dashboard still run locally unless separately hosted.

## Request verification

Only `POST /webhook` is accepted. The receiver requires:

- `webhook-id`: the stable delivery ID.
- `webhook-timestamp`: integer Unix seconds within five minutes of the receiver's clock.
- `webhook-signature`: space-separated signature candidates of the form `v1,<Base64>`.

The HMAC-SHA256 input is the UTF-8 prefix `deliveryId.timestamp.` followed by the
exact request body bytes. JSON is not parsed or reserialized before verification.
The configured secret is Base64-decoded to 32 bytes and imported as a
non-extractable verification key using Web Crypto.

Unsupported or malformed candidates are skipped. Any valid `v1` candidate is
sufficient, allowing a receiver with either the old or new secret to verify
requests during NotifyFlow's rotation overlap.

| Result | Status |
| --- | --- |
| Valid signature and acceptable timestamp | 204, empty response body |
| Missing/invalid headers, expired/future timestamp outside the window, or invalid signature | 401 |
| Wrong path | 404 |
| Method other than POST on `/webhook` | 405 with `Allow: POST` |
| Missing, malformed, or wrong-length configured secret | 500 with a generic configuration error |

Successful verification logs `Webhook verified` with `deliveryId` only. Payloads,
signatures, and secrets are not included in that application log statement.

## Local setup

Use Node.js 24 and npm. From the repository root:

```powershell
Set-Location demo-receiver
npm ci
Copy-Item .dev.vars.example .dev.vars
npm run cf-typegen
npm run dev
```

Copy the example only when first setting up the project; do not overwrite an
existing local configuration. `.dev.vars` is ignored by Git, while the example
contains a public test fixture suitable only for local testing.

Wrangler provides a local URL, normally http://localhost:8787. To test against a
real endpoint locally, privately configure its exact signing secret in `.dev.vars`.
The local file does not configure the deployed secret.

`wrangler.jsonc` declares `WEBHOOK_SIGNING_SECRET` under `secrets.required`.
Run `npm run cf-typegen` after changing bindings. The generated
`worker-configuration.d.ts` supplies Worker runtime and binding types.

## Checks

From `demo-receiver`:

```powershell
npx tsc --noEmit
npx tsc --noEmit -p test/tsconfig.json
npm test -- --run
```

The 48 tests cover routes/methods, required headers, timestamp format and exact
five-minute boundaries, tampered bodies/IDs/timestamps, malformed candidates,
rotation overlap, UTF-8 bodies, configuration failures, and the `SELF` Worker
entry point. Fixed HMAC reference vectors match the independent fixtures in
Java's `WebhookSignerTests`; the receiver implementation is not used to produce
the test signatures.

`vitest.config.mts` supplies a fixed public secret binding, so tests do not require
your actual `.dev.vars` values, Cloudflare login, or production credentials.
The GitHub Actions `Demo receiver` job runs dependency installation, both type
checks, and tests. That job has been added but still needs its first GitHub run;
it does not deploy.

## Deploy under your own Cloudflare account

Use the Workers Free plan for this small demo and monitor its
[current limits](https://developers.cloudflare.com/workers/platform/limits/).
An ordinary Cloudflare account is required; the receiver does not need a custom
domain or a Cloudflare Tunnel process.

1. Run `npx wrangler login` from this directory and authorize the CLI.
2. Find your account's `workers.dev` subdomain under Workers & Pages. With the
   configured Worker name `demo-receiver`, the intended URL is
   `https://demo-receiver.<your-account-subdomain>.workers.dev/webhook`.
3. Create a NotifyFlow endpoint with that URL and keep its generated secret private.
   Do not add a subscription until the Worker is deployed.
4. Set the deployed secret through the interactive prompt:

   ```powershell
   npx wrangler secret put WEBHOOK_SIGNING_SECRET
   ```

   Enter the actual secret from that NotifyFlow endpoint, not the public local
   test fixture. If prompted to create the missing Worker, accept the intended
   `demo-receiver` name. This creates the Worker/secret before deploying our code.
5. Deploy the implementation:

   ```powershell
   npm run deploy
   ```

   Check that the printed deployment URL matches the endpoint you created.

The Worker name is scoped to your Cloudflare account. Changing the name or
account subdomain changes the address; configure a matching NotifyFlow endpoint.
Keep secret values out of `wrangler.jsonc`, command arguments, Git, and screenshots.

## Verify the hosted receiver

First check unsigned rejection. Replace the origin below when using your account:

```powershell
$receiverOrigin = "https://demo-receiver.wengyhongwork.workers.dev"
curl.exe -s -o NUL -w "%{http_code}" -X POST `
    -H "Content-Type: application/json" --data-raw '{}' "$receiverOrigin/webhook"
```

Expect 401. Next, add the subscription `hosted.receiver.demo` to the matching
NotifyFlow endpoint. For a dashboard-only demonstration, click Send test event,
enter that event type and a sample JSON payload, and click Send event. Use New key
when intentionally sending another event. View delivery history shows the refreshed
list, where the delivery should eventually reach HTTP 204.

For the producer API demonstration below, create or use a producer key from the same workspace.
With NotifyFlow running in its regular local HTTP gateway mode, publish:

```powershell
$producerKey = Read-Host "Producer API key" -AsSecureString
$body = @{
    type = "hosted.receiver.demo"
    payload = @{ orderId = 150 }
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

The dashboard should report `DELIVERED` with response status 204. If the application
is in HTTPS tunnel mode, use its public HTTPS origin for event submission instead.

For live receiver logs, start this in another terminal from `demo-receiver`:

```powershell
npx wrangler tail --format pretty
```

Wait for the connection, then submit a new event with a fresh idempotency key.
Look for `Webhook verified` and match its delivery ID to the dashboard. Tail shows
new invocations, not earlier delivery history. If nothing appears, confirm the
event body uses `hosted.receiver.demo` and that the matched endpoint is the hosted
Worker, not the separate `container.delivery.demo` receiver. Stopping tail does
not stop the deployed Worker.

On 2026-11-23, the user verified the deployed endpoint returning 401 for an unsigned
POST, 204 for a signed NotifyFlow delivery, and a matching live verification log.
The first silent log check was traced to events using the old receiver's event
type; publishing the hosted event type produced the expected Worker log.

## Scope and limitations

- One configured signing secret supports one NotifyFlow endpoint. Independent
  endpoints have independent secrets; this Worker is not a multi-endpoint secret registry.
- Timestamp checks limit request age but do not deduplicate valid requests.
  Repeated valid requests within the window can each return 204 and log again.
- There is no durable inbox, business side effect, or exactly-once processing guarantee.
- All valid requests return 204. Use the controllable JShell receiver in the
  [tunnel guide](../docs/cloudflare-tunnel.md#deliver-from-the-backend-container-to-a-local-receiver)
  for deliberate 400/503 retry and replay demonstrations.
- The request body is currently buffered in memory; no application-specific body
  size or request-rate limit is implemented. This is a small demo receiver.
- The hosted receiver remains available independently of your computer, within
  Cloudflare's plan limits. NotifyFlow itself still requires the local containers
  to run before events can be ingested and delivered.

See [webhook security](../docs/webhook-security.md) for the Java signing protocol
and secret rotation, and the [project README](../README.md) for the overall flow.
