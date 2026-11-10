# Manual delivery replay

`POST /api/deliveries/{id}/replay`

Only `FAILED` deliveries can be replayed. The request does not require a body.

## Responses

- `202 Accepted`: replay was accepted for background processing.
- `404 Not Found`: the delivery does not exist.
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

Replace the delivery ID and adjust the port if needed:

```powershell
$baseUrl = "http://localhost:8080"
$deliveryId = "<failed-delivery-id>"

Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/api/deliveries/$deliveryId/replay"
```
