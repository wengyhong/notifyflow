# Deployment review

Repository review performed on 2026-11-23 before selecting a hosting provider.
This records current behavior and remaining work; it is not a production security
certification or a claim that public deployment has been completed.

## Current evidence

| Area | Current implementation | Deployment work |
| --- | --- | --- |
| Credentials | `.env` is ignored; `.env.example` contains placeholders. Password hashes and producer key hashes are stored. | Inject deployment secrets through the chosen platform; keep them out of images, logs, and committed configuration. |
| Browser authentication | Session login with CSRF protection; sessions currently live in the servlet container. | Configure HTTPS, cookie policy, trusted proxy handling, and a session strategy for the selected topology. |
| Registration | `POST /api/auth/register` is publicly accessible and creates a workspace/admin membership. | Decide whether the public portfolio permits registration; define abuse controls and resource limits before making it public. |
| Authorization | Workspace memberships and tenant-scoped resource queries; producer keys grant ingestion only. | Verify the same checks through the deployed reverse proxy and frontend. |
| Webhook requests | HMAC signing, overlap rotation, URL/IP restrictions, connection-time DNS checks, and disabled redirects. | Keep production loopback access disabled and verify destination/egress restrictions in the hosting network. |
| Infrastructure ports | Development Compose publishes PostgreSQL, RabbitMQ, Redis, and RabbitMQ management only on loopback. | Expose only the intended HTTPS entry point; keep data services and broker management private. |
| Management endpoints | Local profile has anonymous GET health/Prometheus on `127.0.0.1:9091`. | Configure a private or authenticated management path; the local profile must not be the public runtime profile. |
| Frontend | SvelteKit uses the Node adapter; Caddy routes the containerized UI and API through localhost:8088. The host development proxy still targets 7829. | Configure the final public origin and HTTPS routing; verify cookies and forwarded headers. |
| Application packaging | Backend Java 21 and frontend Node 24 images build and run as non-root users. The combined Compose stack serves login and history through Caddy. CI image-build steps are added but not yet verified on GitHub. | Verify graceful shutdown, application readiness checks, and deployment image publishing. |
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

The requested hosting budget is zero. No provider or architecture is selected yet,
and no perpetual free hosting or AWS free-tier eligibility is assumed. Check
current service limits, worker support, sleep policies, persistent storage, egress,
and credit expiry before selecting a provider.

The backend, database, RabbitMQ, Redis, and frontend must all fit the plan. An
on-demand local demo with a recording remains an option if an always-on deployment
cannot meet the budget without removing core behavior.

## Remaining verification

1. Select a deployment topology and document its limits and teardown process.
2. Implement deployment configuration and runtime packaging in guided steps.
3. Verify HTTPS login/logout, CSRF, tenant isolation, and producer key revocation.
4. Verify signed delivery, retry, replay, outbox recovery, and receiver deduplication.
5. Verify private infrastructure/management access, restart behavior, and data restore.
6. Record the actual public demo URL and capture screenshots only after deployment works.

See [local development](local-development.md), [authentication](authentication.md),
[webhook security](webhook-security.md), and [observability](observability.md) for
the existing implementation and verification procedures.
