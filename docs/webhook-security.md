# Webhook security

Each endpoint has its own Base64-encoded, 32-byte signing secret.
The secret is returned when the endpoint is created. GET, list, and
enabled-update responses do not include it.

Outgoing requests include:

- `webhook-id`: stable delivery ID across retries.
- `webhook-timestamp`: Unix time in seconds for this attempt.
- `webhook-signature`: one or more space-separated signatures, each formed
  from `v1,` followed by the Base64 HMAC-SHA256 result.

The signed content is:

```text
deliveryId.timestamp.exactRequestBody
```

The HMAC key is the Base64-decoded endpoint secret. The signed text is
encoded as UTF-8. The sender serializes the JSON body once and uses that
same text for signing and sending.

## Receiver verification

The receiver walkthrough was first recorded before account authentication was
added. For current API calls, prepare a session, CSRF token, and tenant header
using [authentication](authentication.md#local-verification-with-powershell).
Use the producer route and Bearer key when submitting events as an application.

The demo receiver:

1. Rejects missing or malformed signing headers.
2. Rejects timestamps more than five minutes in the past or future.
3. Recomputes the signature using the raw request body.
4. Compares signature candidates using `MessageDigest.isEqual`, accepting
   the request if one matches its configured secret.
5. Processes each authenticated delivery ID once.

Valid duplicates return HTTP 204 without repeating processing.

Duplicate tracking is in memory for this demo. A production receiver
needs durable deduplication coordinated with its business changes.
The demo holds one configured secret and supports multiple space-separated
`v1` signature candidates. It skips unsupported or malformed candidates
and continues looking for a valid match.

## Verified manually

- Valid signed request: 204.
- Unsigned request: 401.
- Modified body: 401.
- Correctly signed but expired request: 401.
- Same valid request twice: both 204, processed once.
- Delivery before rotation verifies with the original secret.
- During overlap, delivery verifies with a receiver still using the old secret.
- During overlap, delivery verifies after switching the receiver to the new secret.
- A repeated rotation during active overlap returns 409.

These checks were performed with a local JShell receiver. A valid delivery
was also verified using the saved standalone receiver below.

## Running the standalone receiver

Use Java 21 and run commands from the repository root. Start PostgreSQL,
RabbitMQ, and NotifyFlow first. Replace the example NotifyFlow port `1897`
with the port printed by your application.

Start NotifyFlow with the local profile for this loopback receiver demo:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --server.port=0"
```

The local profile loads `application-local.properties`, enabling
`notifyflow.webhook.allow-loopback=true`. This permits loopback addresses
only; other private and special-purpose addresses remain blocked. Without
this explicit setting, loopback is blocked by default. Use the actual
Tomcat port printed at startup in the API commands below.

The example is in
[`examples/webhook-receiver/WebhookReceiver.java`](../examples/webhook-receiver/WebhookReceiver.java).
It runs directly with Java; no Maven build is needed for the receiver.

### Register and subscribe an endpoint

In PowerShell:

```powershell
$baseUrl = "http://localhost:1897"
$receiverPort = 1786

$secureEndpoint = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/endpoints" `
  -ContentType "application/json" `
  -Body (@{
      name = "Standalone signed webhook demo"
      url = "http://127.0.0.1:$receiverPort/webhook"
  } | ConvertTo-Json)

Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/subscriptions/$($secureEndpoint.id)" `
  -ContentType "application/json" `
  -Body '{"eventType":"security.receiver.demo"}'
```

Use an event type dedicated to this receiver. If another endpoint is
already subscribed to `security.receiver.demo`, choose a new event type
and use it in both the subscription and event commands.

### Start the receiver

In the same PowerShell session that holds `$secureEndpoint`:

```powershell
$env:NOTIFYFLOW_WEBHOOK_SECRET = $secureEndpoint.signingSecret

java --add-modules jdk.httpserver examples/webhook-receiver/WebhookReceiver.java $receiverPort
```

Expect:

```text
Receiver URL: http://127.0.0.1:1786/webhook
```

Port `1786` is an example. It must be available, and the endpoint URL must
match the receiver's port. Stop any old JShell receiver occupying it first.
Omitting the port argument lets the receiver choose an available port,
but an existing endpoint URL will not automatically follow that change.

The secret comes from the endpoint creation response. Do not put a real
secret in the source file, documentation, or a committed script.

### Submit an event

Keep the receiver running. In a separate PowerShell terminal:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:1897/api/events" `
  -Headers @{"Idempotency-Key" = [guid]::NewGuid().ToString()} `
  -ContentType "application/json" `
  -Body '{"type":"security.receiver.demo","payload":{"orderId":512}}'
```

Replace the NotifyFlow port here too if necessary. The receiver should print:

```text
Verified webhook <delivery-id>: {"orderId":512}
```

Stop the receiver with **Ctrl+C**. Restarting it clears its in-memory
duplicate tracking. This example listens only on the local loopback
interface and demonstrates verification; it is not a production receiver.

## Secret backfill

- V9 adds the signing-secret column.
- V10 uses PostgreSQL `pgcrypto` to generate secrets for existing endpoints
  that have none, preserving secrets already assigned.
- V11 makes the column required.
- V12 adds the previous secret and its expiry, with a constraint requiring
  both values to be present or both absent.
- V13 adds the endpoint version used by JPA optimistic locking to reject
  stale updates that could overwrite rotated secrets.

New endpoints receive secrets from Java's `SecureRandom` through
`WebhookSecretGenerator`.

Never put real endpoint secrets in this document or other committed files.

## Secret rotation

Rotation explicitly replaces the current signing secret of one existing
endpoint. Its ID, subscriptions, enabled state, and delivery history are
preserved. An endpoint and its current secret have no automatic expiry.

```text
POST /api/endpoints/{id}/signing-secret/rotate
```

No request body is required:

```powershell
$rotation = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/endpoints/$($secureEndpoint.id)/signing-secret/rotate"
```

| Status | Meaning |
| --- | --- |
| 200 | Returns `id`, the new `signingSecret`, and `previousSigningSecretExpiresAt` |
| 404 | Endpoint does not exist |
| 409 | A previous rotation's overlap is still active |

Save `$rotation.signingSecret` securely for the receiver. The rotation
response does not expose the previous secret. Ordinary endpoint read,
list, and enabled-update responses expose neither secret. Its response
record's `toString()` also excludes the new secret.

The service locks the endpoint in a transaction, checks for an active
overlap, and generates a new secret. The previous current secret is kept
for one hour. JPA dirty checking persists the update at commit, and
optimistic versioning protects against stale endpoint writes. Concurrent
rotation requests are serialized: one succeeds and the other sees an
active overlap and conflicts.

During overlap, the signature header contains current then previous
signatures for the same ID, timestamp, and exact body:

```text
webhook-signature: v1,<current-signature> v1,<previous-signature>
```

The worker snapshots the secrets into `DeliveryWork`. The sender checks
the previous secret's expiry using the actual send time, so an overlap
that expires between claim and send does not produce a previous-secret
signature. At or after expiry, only the current signature is sent.

An already claimed delivery retains its snapshot; rotation does not
rewrite work already in flight. Receivers should account for in-flight
requests when coordinating a key change.

An endpoint stores at most a current and a previous secret. Another
rotation is allowed at or after the overlap expiry. Expired previous
secrets remain stored until a later rotation replaces them; expiry stops
their use for signing, not their storage. Expiry does not trigger a new
rotation automatically.

### Switching the demo receiver

Keep the receiver on the old secret initially and submit an event of its
subscribed type. It should still verify during overlap. Then, in the API
terminal:

```powershell
Set-Clipboard -Value $rotation.signingSecret
```

Stop the receiver with Ctrl+C. In its terminal, load the new secret and
restart the updated source:

```powershell
$env:NOTIFYFLOW_WEBHOOK_SECRET = Get-Clipboard
Set-Clipboard -Value ""

java --add-modules jdk.httpserver examples/webhook-receiver/WebhookReceiver.java 1786
```

Submit another event. It should verify using the new secret. Repeating
the rotation API call before expiry should return 409. Automated tests
cover the expiry boundary without waiting an hour or changing live data.

This implementation supports planned rotation. A compromised old secret
remains capable of forging signatures accepted by receivers still trusting
it. Immediate emergency revocation is not implemented; it would need a
separate mechanism and coordination with receivers, including in-flight
work. The receiver examples were recorded before the authentication milestone.
Current management requests require a user session, valid CSRF token, and
`X-Tenant-ID` with administrator membership; producer keys only publish events.
See [authentication](authentication.md) for the current API workflow.
Secrets are stored as database values without
application-level encryption in the current implementation.

## Destination address policy

`WebhookUrlPolicy` checks URL structure and every resolved destination
address during endpoint registration and before each delivery.
`WebhookAddressPolicy` classifies individual resolved IP addresses. A
hostname is rejected if any answer is blocked, except explicitly permitted
loopback addresses in local development.

Apache HttpClient uses `WebhookDnsResolver` when opening hostname
connections. The adapter returns the addresses checked by the same policy,
so a changed DNS answer at connection setup must pass validation too.
Upfront URL validation also checks literal IP destinations. Established
pooled connections can be reused without another connection-time lookup.

Redirects, automatic HTTP retries, and cookie management are disabled.
NotifyFlow's delivery retry policy remains responsible for retries. The
client has a five-second connection timeout, a five-second connection-pool
wait timeout, and ten-second socket/response timeouts. These are separate
timeouts, not a single ten-second deadline for the entire delivery.

IPv4 exclusions:

| Range | Reason |
| --- | --- |
| `0.0.0.0/8` | This-network addresses |
| `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` | Private networks |
| `100.64.0.0/10` | Shared address space |
| `127.0.0.0/8` | Loopback |
| `169.254.0.0/16` | Link-local, including common metadata addresses |
| `192.0.0.0/24` | Protocol assignments; whole block excluded conservatively |
| `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24` | Documentation |
| `192.88.99.0/24` | Deprecated relay allocation; whole block excluded |
| `198.18.0.0/15` | Benchmarking |
| `224.0.0.0/4` | Multicast |
| `240.0.0.0/4` | Reserved, including limited broadcast |

IPv6 destinations must be within `2000::/3`, with additional exclusions
for `2001::/23` (protocol assignments), `2002::/16` (6to4), and the
documentation ranges `2001:db8::/32` and `3fff::/20`. Excluding the entire
protocol-assignment block is a conservative choice that includes some
globally reachable special-purpose exceptions.

The scope rule rejects IPv6 loopback, unspecified, link-local, unique-local,
multicast, and translation prefixes outside `2000::/3`. The earlier explicit
Java category and unique-local checks remain for clarity. IPv4-mapped
literals normalized by Java to IPv4 undergo the IPv4 rules; an address
explicitly represented as IPv6 outside the allowed scope is blocked.

These are application policy rules, not proof of global routability or a
replacement for deployment-level network egress restrictions. Registry
changes require policy review. Egress restrictions remain part of the
deployment design.

Verified manually:

- Signed delivery succeeds through Apache HttpClient with the local profile.
- A private-network endpoint registration returns 400 with local enabled.
- A loopback endpoint registration returns 400 without local enabled.

Automated tests also cover changed DNS answers during connection setup,
mixed public/blocked answers, blocked literal destinations receiving no
request, and redirects not being followed. Tests use mocked DNS and local
HTTP servers.

The range sources are the IANA
[IPv4 special-purpose registry](https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml),
[IPv6 special-purpose registry](https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml),
and [IPv6 address-space allocation](https://www.iana.org/assignments/ipv6-address-space/ipv6-address-space.xhtml).

## Reference

The signing format follows the HMAC scheme described in the
[Standard Webhooks specification](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md).
