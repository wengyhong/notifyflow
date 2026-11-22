# Manual delivery replay

`POST /api/deliveries/{id}/replay`

Only `FAILED` deliveries can be replayed. The request does not require a body.

The caller needs an authenticated user session, a valid CSRF token, and
`X-Tenant-ID` selecting a tenant where they are an `ADMIN`. Both the delivery's
event and endpoint must belong to that tenant. A producer API key grants no
management access. See [authentication](authentication.md#tenant-ownership-and-authorization).

## Responses

- `202 Accepted`: replay was accepted for background processing.
- `400 Bad Request`: a required tenant header is missing or malformed.
- `401 Unauthorized`: the caller is not authenticated.
- `403 Forbidden`: membership/admin access or CSRF validation failed.
- `404 Not Found`: the delivery is missing or its event/endpoint belongs elsewhere.
- `409 Conflict`: the delivery is not `FAILED`.

A successful response contains the delivery ID and its status when replay was accepted:

```json
{
  "id": "bdb1b2e7-772c-4afa-a2a9-85c0a3d9b216",
  "status": "PENDING"
}
```

Acceptance does not mean the webhook has already been delivered. Processing happens asynchronously.

## Behavior

Replay preserves the delivery ID, event, endpoint, and previous attempts.

Attempt numbers continue from the existing history. Each replay receives a fresh
budget of five total attempts, including its first request.

For example, replay after failed attempts 1–5 starts at attempt 6 and allows
attempts 6–10. Processing stops as soon as an attempt succeeds. Non-retryable
HTTP failures stop the cycle immediately; temporary failures use exponential
backoff with jitter while attempts remain.

Concurrent replay requests are serialized using the delivery's database lock.
Once one request makes the delivery pending, another replay request is rejected.

## Delivery history and dashboard

Authenticated workspace members (`ADMIN` and `VIEWER`) can read:

| Route | Response |
|---|---|
| `GET /api/deliveries?page=0&size=20` | Paginated delivery summaries, newest creation time and ID first |
| `GET /api/deliveries?status=FAILED&page=0&size=20` | The same page shape, filtered by a delivery status |
| `GET /api/deliveries/{id}` | One delivery summary, or 404 for a missing/foreign delivery |
| `GET /api/deliveries/{id}/attempts?page=0&size=20` | Paginated HTTP attempts, highest attempt number first |

All GET requests require the session and `X-Tenant-ID`; they do not need a CSRF
token. Both the event and endpoint must belong to the selected tenant. This
applies to delivery pages, details, attempt pages, and their pagination totals.
An owned delivery without attempts returns an empty page; a missing or foreign
delivery returns 404 instead. Successful responses use `Cache-Control: no-store`.

Summaries contain identifiers, status, creation/next-attempt times, and the
retry-cycle start attempt. Attempts contain their number, start/completion times,
HTTP status, and error message. They do not include event payloads, idempotency
keys, signing secrets, or serialized JPA relationships.

The dashboard **Delivery history** section supports status filtering, pagination,
manual refresh, and **View details and attempts**. Failed delivery details show
**Replay failed delivery** to administrators. Replay uses the existing
CSRF-protected POST, handles a 409 if the status has changed, and refreshes both
the list and selected details after acceptance. Processing remains asynchronous;
use **Refresh details and attempts** to see later outcomes. Changing the selected
workspace clears the current history view and details.

### Dashboard verification — 2026-11-21

The browser walkthrough verified:

- A demo URL returned HTTP 301 and the delivery became `FAILED`; the attempt
  view displayed the recorded status.
- A local receiver at `http://127.0.0.1:1786/webhook`, subscribed to
  `delivery.success.demo`, received order 129 and returned HTTP 204. The
  dashboard showed `DELIVERED` and the successful attempt.
- The receiver was changed to return HTTP 400. Publishing order 130 produced
  `FAILED` with attempt 1 recording HTTP 400.
- After changing the same receiver to return HTTP 204, the administrator used
  **Replay failed delivery**. The delivery became `DELIVERED`; its ID stayed
  the same, attempt 1 remained HTTP 400, attempt 2 recorded HTTP 204, and
  `retryCycleStartAttempt` became 2.

The local receiver acknowledged requests for this delivery-history smoke test;
signature verification is covered by the separate webhook-security walkthrough.

## PowerShell replay example

First prepare the admin session using [authentication](authentication.md#local-verification-with-powershell).
Replace the delivery ID and owning tenant ID, and adjust the application port:

```powershell
$baseUrl = "http://localhost:7829"
$deliveryId = "<failed-delivery-id>"
$tenantId = "<delivery-tenant-id>"

$csrf = Invoke-RestMethod `
    -Uri "$baseUrl/api/auth/csrf" `
    -WebSession $notifyflowSession

Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/api/deliveries/$deliveryId/replay" `
    -WebSession $notifyflowSession `
    -Headers @{
        "X-CSRF-TOKEN" = $csrf.token
        "X-Tenant-ID" = $tenantId
    }
```
