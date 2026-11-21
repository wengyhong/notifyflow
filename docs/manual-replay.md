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

## PowerShell example

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
