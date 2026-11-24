# Deployment review

Repository review performed on 2026-11-23 before selecting a hosting provider.
This records current behavior and remaining work; it is not a production security
certification or a claim that public deployment has been completed.

## Current evidence

| Area | Current implementation | Deployment work |
| --- | --- | --- |
| Credentials | `.env` is ignored; `.env.example` contains placeholders. Password hashes and producer key hashes are stored. | Inject deployment secrets through the chosen platform; keep them out of images, logs, and committed configuration. |
| Browser authentication | HTTPS tunnel login/logout and an authenticated endpoint write were verified. Tunnel session cookies use Secure, HttpOnly, and SameSite=Lax; sessions live in the servlet container. | Review proxy trust and session strategy for any permanent topology; repeat tests after changing the public entry point. |
| Registration | `POST /api/auth/register` is publicly accessible and creates a workspace/admin membership. | Decide whether the public portfolio permits registration; define abuse controls and resource limits before making it public. |
| Authorization | Workspace memberships and tenant-scoped resource queries; producer keys grant ingestion only. | Verify the same checks through the deployed reverse proxy and frontend. |
| Webhook requests | HMAC signing, overlap rotation, URL/IP restrictions, connection-time DNS checks, and disabled redirects. | Keep production loopback access disabled and verify destination/egress restrictions in the hosting network. |
| Infrastructure ports | Development Compose publishes PostgreSQL, RabbitMQ, Redis, and RabbitMQ management only on loopback. | Expose only the intended HTTPS entry point; keep data services and broker management private. |
| Management endpoints | Local profile has anonymous GET health/Prometheus on `127.0.0.1:9091`. | Configure a private or authenticated management path; the local profile must not be the public runtime profile. |
| Frontend | SvelteKit uses the Node adapter; Caddy routes UI/API through one address. The public ORIGIN is configurable for the verified temporary HTTPS tunnel. | Configure a stable origin if permanent hosting is chosen; the Quick Tunnel hostname changes. |
| Application packaging | Backend Java 21 and frontend Node 24 images build and run as non-root users. The combined Compose stack serves login and history through Caddy, and CI image builds passed on GitHub. | Verify graceful shutdown, application readiness checks, and deployment image publishing. |
| Persistence | PostgreSQL/RabbitMQ named volumes; Redis has no named volume in development Compose. | Define durable storage, backup/restore, and migration procedures. Test a restore rather than treating a volume as a backup. |
| Failure recovery | Outbox publisher confirms, due-retry scheduler, and manual replay are implemented. | Exercise process termination around claim/send/save boundaries; define recovery for interrupted `PROCESSING` deliveries. |
| Operations | Send metrics and correlated logs; CI checks backend/frontend separately. | Decide log retention, metrics collection, alerts, and cleanup for the hosted demo. |

The local `.env` import treats the file as Java properties. Docker Compose uses
dotenv parsing, so quoting and escaping can differ. This is a development
convenience, not a production secrets mechanism. Existing process environment
variables override imported values.

The send logs deliberately exclude payloads, destination URLs, secrets, and raw
exceptions. This applies to the reviewed send statements, not every dependency's
logging or the entire application.

## Public demo constraints

The requested hosting budget is zero. An on-demand local deployment with a
Cloudflare Quick Tunnel is verified; an always-on provider is not selected,
and no perpetual free hosting or AWS free-tier eligibility is assumed. Check
current service limits, worker support, sleep policies, persistent storage, egress,
and credit expiry before selecting a provider.

The backend, database, RabbitMQ, Redis, and frontend must all fit the plan. An
on-demand local demo with a recording remains an option if an always-on deployment
cannot meet the budget without removing core behavior. See the
[tunnel setup and shutdown guide](cloudflare-tunnel.md) for the current demo.

## Verified container delivery checkpoint

On 2026-11-23, the user verified the containerized backend delivering through a
separate HTTPS receiver tunnel: HTTP 204 success, automatic retry from 503 to 204,
and manual replay from 400/FAILED to 204/DELIVERED while retaining delivery identity
and attempt history. See the [reproducible walkthrough](cloudflare-tunnel.md#deliver-from-the-backend-container-to-a-local-receiver).
The receiver did not validate signatures or deduplicate requests. Screenshots and
a recording remain deferred.

## Remaining verification for a permanent deployment

The separate [Cloudflare Worker receiver](../demo-receiver/README.md) was also
deployed and verified on 2026-11-23: unsigned POST returned 401, a signed NotifyFlow
delivery returned 204, and live Worker logs showed the matching delivery ID.
Its 48 local tests include independent Java signer reference vectors, timestamp
boundaries, tamper rejection, and rotation candidates. The new receiver CI job
still needs a GitHub run. The Worker has no durable deduplication or business
processing, and deploying it does not host the main application.

1. Select an always-on deployment topology if desired, document its costs/limits,
   and define infrastructure as code and teardown procedures.
2. Add application readiness checks and image publishing for the chosen host.
3. Repeat HTTPS/session checks and verify tenant isolation and producer key revocation
   in that environment.
4. Repeat signature checks in the final environment; verify outbox recovery during
   broker outages, durable receiver deduplication if required, and recovery after
   worker termination.
5. Verify private infrastructure/management access, restart behavior, and data restore.
6. Record a stable demo URL only if one is provisioned; capture screenshots and a recording later.

See [local development](local-development.md), [authentication](authentication.md),
[webhook security](webhook-security.md), and [observability](observability.md) for
the existing implementation and verification procedures.
