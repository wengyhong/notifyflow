# Authentication, tenant isolation, and producer API keys

Milestone 8 now includes account registration, session-cookie login, tenant-scoped
business operations, and producer API keys. Human users manage a workspace through
their session and tenant membership. External applications publish events using
a key bound to one tenant.

Endpoint reads, writes, subscriptions, secret rotation, and manual replay enforce
tenant access. Events, delivery routing, database idempotency uniqueness, and Redis
idempotency keys are tenant-scoped. Producer keys grant event publishing permission;
they do not grant tenant administrator membership.

## Identity model

| Table | Purpose | Important rules |
| --- | --- | --- |
| `tenants` | Organization or workspace | UUID ID, display name, creation time; names need not be unique |
| `app_users` | User identity and stored password hash | Case-insensitive unique email, enabled flag, creation time |
| `tenant_memberships` | A user's role in one tenant | Required user and tenant foreign keys; unique `(tenant_id, user_id)` |
| `api_keys` | A producer credential belonging to one tenant | Unique key hash; required tenant; nullable revocation timestamp |

Flyway migrations V14, V15, and V16 create these tables. The
`uq_app_users_email_lower` unique index applies to `LOWER(email)`, and
`AppUserRepository.findByEmail()` uses the same expression for lookup.

Membership roles are stored as the strings `ADMIN` or `VIEWER`, constrained by
`ck_tenant_memberships_role`. A user can belong to multiple tenants with different
roles. Roles belong to memberships rather than the user table.

## Registration

`POST /api/auth/register` accepts JSON:

```json
{
  "email": "alice@example.com",
  "password": "a sufficiently long demo passphrase",
  "tenantName": "Company A"
}
```

Successful registration returns HTTP 201 with only `userId` and `tenantId`.
It does not automatically authenticate the client. A subsequent login is required.

`AccountRegistrationService.register()` executes inside one database transaction:

1. Validate the password policy before encoding or writing rows.
2. Read one timestamp from the injected `Clock`.
3. Encode the password and insert the user with `saveAndFlush()`.
4. Insert the tenant.
5. Insert an `ADMIN` membership connecting the new user and tenant.
6. Commit the changes together.

The service assigns the creator's role; the registration request does not accept
a role or an existing tenant ID. A failure during tenant or membership persistence,
or at commit, rolls back the registration, including the flushed user insert.
Flushing sends SQL to PostgreSQL; it is not a commit.

If the user insert violates `uq_app_users_email_lower`, the service translates
that specific constraint failure into `EmailAlreadyRegisteredException`.
Other database integrity failures retain their original meaning. Concurrent
case-variant registrations are resolved by PostgreSQL's unique index, without
relying on a preliminary email lookup for correctness.

### Validation and password handling

`RegisterAccountRequest` requires a nonblank valid email of at most 254 characters
and a nonblank tenant name of at most 100 characters. Its `@AssertTrue` boolean
getter connects the password policy to Bean Validation, triggered by the
controller's `@Valid @RequestBody` parameter.

`RegistrationPasswordPolicy` requires a nonblank password with at least 15
Unicode code points and at most 72 UTF-8 bytes. The byte maximum matches the
current bcrypt encoder. Character count and byte count differ for non-ASCII
input: 18 emoji can occupy 72 bytes, while 19 occupy 76. Passwords are checked
as supplied; they are not trimmed or truncated.

The service also enforces this policy so Java callers cannot bypass it by
avoiding the HTTP controller. `InvalidRegistrationPasswordException` maps to
HTTP 400. Duplicate email registration maps to HTTP 409. Both custom error
handlers return `ProblemDetail` with the application message, without exposing
the nested database diagnostic.

`PasswordConfiguration` supplies a delegating `PasswordEncoder`. New hashes
currently use bcrypt with an algorithm identifier in the stored string.
`AppUser` receives the encoded hash, not the raw password. Password verification
uses `matches(rawPassword, storedHash)` rather than comparing independently
generated hash strings. The request record's `toString()` is redacted, and
registration responses contain neither the password nor its hash.

## Session login and CSRF

| Route | Request | Success | Notes |
| --- | --- | --- | --- |
| `GET /api/auth/csrf` | No authentication required | 200 with token metadata | Provides a CSRF token and creates a session when needed |
| `POST /api/auth/register` | JSON with valid CSRF token | 201 with new IDs | Anonymous registration is allowed |
| `POST /api/auth/login` | Form fields `email` and `password`, with valid CSRF token | 204 | Saves authentication in the session |
| `POST /api/auth/logout` | Session cookie and valid CSRF token | 204 | Invalidates the session and deletes `JSESSIONID` |

`permitAll()` removes an authentication requirement; it does not bypass CSRF
checks. Missing or invalid CSRF tokens on protected writes produce HTTP 403.
Invalid login credentials, including a disabled account, produce HTTP 401.
Unauthenticated access to protected business API routes produces HTTP 401
without a login-page redirect. Request caching is disabled, and HTTP Basic
authentication is disabled.

Login uses `application/x-www-form-urlencoded`, not a JSON credentials body.
Spring Security's form-login filter reads the `email` and `password` parameters.
`DatabaseUserDetailsService` loads the user by email and returns `AppUserDetails`,
which retains the user's UUID for membership checks. Spring's
authentication provider checks the password and enabled flag. Credentials are
erased from the authenticated principal after successful authentication.

The principal has no global tenant roles. The authentication result can contain
Spring's password-factor authority; that records how authentication occurred and
does not grant a tenant's `ADMIN` permissions.

Clients must retain the session cookie and send the CSRF token using the header
name returned by `/api/auth/csrf`. Login clears the previous token, so clients
must fetch a fresh token after login before making another write, including
logout. After logout, a client can fetch a new anonymous token for another login.
Sessions currently use the servlet container's session storage; shared session
storage across application instances has not been configured.

## Tenant ownership and authorization

Session-based business routes use the custom `X-Tenant-ID` header to select a
workspace. `TenantAccessService` checks membership using that tenant ID and the
authenticated `AppUserDetails.userId`. Request headers or JSON cannot choose the
user's identity or membership role. A `TenantAccess` record carries the verified
tenant ID and role into application services.

| Operation | Required access |
| --- | --- |
| Get or list endpoints | `ADMIN` or `VIEWER` membership in the selected tenant |
| Create or enable/disable endpoints | `ADMIN` membership |
| Create endpoint subscriptions or rotate signing secrets | `ADMIN` membership |
| Submit events through `POST /api/events` | User session and `ADMIN` membership |
| Replay a failed delivery | `ADMIN` membership; both its event and endpoint must belong to the selected tenant |
| Create or revoke producer keys | User session and `ADMIN` membership |
| Submit events through `POST /api/producer/events` | An active producer key; tenant comes from the key |

Session writes also require a valid CSRF token. Missing or malformed tenant headers
return 400, nonmembers and insufficient roles return 403, and a resource belonging
to another tenant is returned as 404 after membership and role checks. Producer
requests do not use the tenant header to select an owner.

V17-V21 implement the ownership transition: create the legacy workspace when
needed, backfill old resources, introduce tenant-scoped event idempotency
uniqueness, and finally require `tenant_id` on both events and endpoints. Assigned
owners are preserved. Backfill does not automatically grant any user membership
in the legacy workspace. Public resource constructors now require a tenant.

`uq_events_tenant_idempotency_key` makes `(tenant_id, idempotency_key)` unique.
The Redis key is `notifyflow:event:idempotency:<tenantId>:<idempotencyKey>`.
Different tenants may reuse the same event type and idempotency key independently.
Delivery creation selects only the publishing tenant's matching enabled endpoints.

## Producer API keys

V22 creates `api_keys`. A key has a UUID, tenant, display name, unique `key_hash`,
creation time, and optional `revoked_at`. Names need not be unique. There is no
automatic key expiry; a key remains active until revoked.

`ApiKeyGenerator` uses `SecureRandom` to generate 32 random bytes and encodes them
as URL-safe Base64 without padding. The `nf_` prefix plus 43 encoded characters
produces a 46-character raw key. `ApiKeyHasher` hashes the entire UTF-8 key,
including its prefix, with SHA-256 and stores 64 lowercase hexadecimal characters.
This is a randomly generated credential, distinct from a human-chosen account
password, which continues to use the password encoder.

`ApiKeyService.create()` checks the admin role before generation, hashing, or
persistence. Its transaction stores the hash and returns the raw key in the
creation result. The raw key is not stored and has no later retrieval route.
The creation HTTP response uses `Cache-Control: no-store`; the result and response
records redact the raw value from `toString()`.

| Route | Authentication and input | Success |
| --- | --- | --- |
| `POST /api/key` | User session, CSRF token, `X-Tenant-ID`, JSON with a nonblank `name` of at most 100 characters | 201 with `id`, `name`, `rawKey`, `createdAt` |
| `POST /api/key/{id}/revoke` | User session, CSRF token, `X-Tenant-ID`; no body required | 204 with an empty body |
| `POST /api/producer/events` | `Authorization: Bearer <rawKey>`, `Idempotency-Key`, and event JSON | 202 with `id`, `type`, `createdAt` |

Revocation uses a tenant-scoped pessimistic write lock. Repeated revocation of an
owned key returns 204 and preserves the first timestamp, including concurrent
requests. Missing or foreign keys return 404; viewers and nonmembers receive 403.
Revocation rolls back with an enclosing transaction.

### Producer request security

`ProducerSecurityConfiguration` is checked first through `@Order(1)` and matches
`/api/producer/**`. It is stateless and does not use a user session for authentication.
`ApiKeyAuthenticationFilter` is instantiated in that chain, rather than registered
as a global servlet filter. It reads the Bearer header and delegates verification
to `ApiKeyAuthenticationService`.

The authentication service rejects malformed keys, hashes well-formed keys, and
looks up an active row. `ApiKeyPrincipal` contains only the stored key ID and
tenant ID. The filter creates an authenticated token with null credentials and
only `EVENT_PUBLISH` authority, then clears the security context after the request,
including when downstream processing throws. Authentication does not cache
positive key lookups, so a committed revocation blocks the next request.

Only `POST /api/producer/events` is permitted in this chain. Other producer paths
or methods are denied. The existing session chain continues to handle management
routes and `/api/events`; a producer key alone cannot authenticate those routes.
CSRF is disabled only in the stateless producer chain, where the client explicitly
sends an authorization header. Session-based management still requires CSRF.

Missing, malformed, unknown, or revoked credentials return 401. The authentication
service propagates database failures instead of returning an empty authentication result.
Producer ingestion reuses the same validation, tenant-scoped idempotency,
transactional delivery creation, and durable outbox as session-based ingestion.
The controller ignores a supplied `X-Tenant-ID` when determining ownership.

Producer keys identify publishing applications. They are separate from each
endpoint's webhook signing secret, which authenticates outbound webhook messages.
Revoking a producer key prevents future submissions; it does not cancel deliveries
that were already accepted.

## Local verification with PowerShell

Run PostgreSQL, RabbitMQ, Redis, and NotifyFlow. In the November 20, 2026 demo,
Redis was published on host port 16379 because Windows reserved port 6379.
The application terminal used this override before starting NotifyFlow:

```powershell
$env:SPRING_DATA_REDIS_PORT = "16379"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --server.port=0"
```

Use the Redis host port configured for your own container. The `local` profile
allows local webhook receivers; it does not bypass authentication.

In a second PowerShell terminal, replace the application port as necessary and
fetch a CSRF token while retaining its session cookie:

```powershell
$baseUrl = "http://localhost:7829"
$csrf = Invoke-RestMethod `
  -Uri "$baseUrl/api/auth/csrf" `
  -SessionVariable notifyflowSession

$csrf | Select-Object headerName, parameterName
```

Expect `X-CSRF-TOKEN` and `_csrf`. Keep this terminal open for the entire flow.

### Register

Choose a demo password meeting the policy; it is read without echoing it:

```powershell
$demoEmail = "demo-$([guid]::NewGuid().ToString('N'))@example.com"
$demoPassword = Read-Host "Demo account password" -AsSecureString

$registered = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/auth/register" `
  -WebSession $notifyflowSession `
  -Headers @{ ($csrf.headerName) = $csrf.token } `
  -ContentType "application/json" `
  -Body (@{
      email = $demoEmail
      password = [System.Net.NetworkCredential]::new('', $demoPassword).Password
      tenantName = "NotifyFlow demo workspace"
  } | ConvertTo-Json)

$registered
```

Expect `userId` and `tenantId`. They identify the new records; registration itself
does not create an authenticated session.

### Log in

```powershell
Invoke-WebRequest `
  -Method Post `
  -Uri "$baseUrl/api/auth/login" `
  -WebSession $notifyflowSession `
  -Headers @{ ($csrf.headerName) = $csrf.token } `
  -ContentType "application/x-www-form-urlencoded" `
  -Body @{
      email = $demoEmail
      password = [System.Net.NetworkCredential]::new('', $demoPassword).Password
  } `
  -UseBasicParsing |
  Select-Object StatusCode
```

Expect 204. Confirm the saved session authenticates a later read without
resending the password:

```powershell
Invoke-WebRequest `
  -Uri "$baseUrl/api/endpoints?size=1" `
  -WebSession $notifyflowSession `
  -Headers @{ "X-Tenant-ID" = $registered.tenantId } `
  -UseBasicParsing |
  Select-Object StatusCode
```

Expect 200. The selected tenant is the workspace created during registration.
Cross-tenant and viewer/admin boundaries are also covered by automated tests.

### Create a producer API key

After login, fetch a fresh CSRF token and create a key in the registered tenant:

```powershell
$csrf = Invoke-RestMethod `
  -Uri "$baseUrl/api/auth/csrf" `
  -WebSession $notifyflowSession

$createdKey = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/key" `
  -WebSession $notifyflowSession `
  -Headers @{
      "X-CSRF-TOKEN" = $csrf.token
      "X-Tenant-ID" = $registered.tenantId
  } `
  -ContentType "application/json" `
  -Body (@{ name = "Producer demo" } | ConvertTo-Json)

$createdKey | Select-Object id, name, createdAt
```

Creation returns 201. Keep `$createdKey.rawKey` in this terminal for the next calls;
the displayed metadata omits the raw value.

### Publish without a user session

```powershell
$producerIdempotencyKey = [guid]::NewGuid().ToString()
$producerBody = @{
    type = "api.key.demo"
    payload = @{ orderId = 128 }
} | ConvertTo-Json -Depth 5

$acceptedEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/producer/events" `
  -Headers @{
      "Authorization" = "Bearer $($createdKey.rawKey)"
      "Idempotency-Key" = $producerIdempotencyKey
  } `
  -ContentType "application/json" `
  -Body $producerBody

$acceptedEvent
```

Expect 202 with event metadata. This call sends no session cookie, CSRF token, or
tenant header. A matching subscription is not needed for event acceptance; actual
delivery creation additionally requires an enabled subscribed endpoint in this tenant.

Retry the same body and key:

```powershell
$retriedEvent = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/producer/events" `
  -Headers @{
      "Authorization" = "Bearer $($createdKey.rawKey)"
      "Idempotency-Key" = $producerIdempotencyKey
  } `
  -ContentType "application/json" `
  -Body $producerBody

$retriedEvent.id -eq $acceptedEvent.id
```

Expect `True`: the retry reuses the original event. Different content under the
same tenant and key returns 409 instead of replacing the event.

### Revoke and retry

Revocation is a management operation and uses the admin session:

```powershell
$revocation = Invoke-WebRequest `
  -UseBasicParsing `
  -Method Post `
  -Uri "$baseUrl/api/key/$($createdKey.id)/revoke" `
  -WebSession $notifyflowSession `
  -Headers @{
      "X-CSRF-TOKEN" = $csrf.token
      "X-Tenant-ID" = $registered.tenantId
  }

$revocation.StatusCode
```

Expect 204. Repeat the producer request with the revoked key:

```powershell
try {
    $retry = Invoke-WebRequest `
      -UseBasicParsing `
      -Method Post `
      -Uri "$baseUrl/api/producer/events" `
      -Headers @{
          "Authorization" = "Bearer $($createdKey.rawKey)"
          "Idempotency-Key" = $producerIdempotencyKey
      } `
      -ContentType "application/json" `
      -Body $producerBody `
      -ErrorAction Stop

    $retry.StatusCode
} catch {
    [int]$_.Exception.Response.StatusCode
}
```

Expect 401 even though the event was previously accepted. Key authentication runs
before ingestion and its idempotency cache lookup.

### Refresh CSRF and log out

```powershell
$csrf = Invoke-RestMethod `
  -Uri "$baseUrl/api/auth/csrf" `
  -WebSession $notifyflowSession

Invoke-WebRequest `
  -Method Post `
  -Uri "$baseUrl/api/auth/logout" `
  -WebSession $notifyflowSession `
  -Headers @{ ($csrf.headerName) = $csrf.token } `
  -UseBasicParsing |
  Select-Object StatusCode
```

Expect 204. Then repeat the protected read with the same client session object:

```powershell
try {
    Invoke-WebRequest `
      -Uri "$baseUrl/api/endpoints?size=1" `
      -WebSession $notifyflowSession `
      -Headers @{ "X-Tenant-ID" = $registered.tenantId } `
      -UseBasicParsing
} catch {
    [int]$_.Exception.Response.StatusCode
}
```

Expect 401.

### Original session verification: November 20, 2026

| Step | Observed result |
| --- | --- |
| Fetch CSRF token | Token metadata returned |
| Register demo account | User and tenant IDs returned |
| Login | HTTP 204 |
| Protected endpoint-list read with session | HTTP 200 |
| Refresh token and log out | HTTP 204 |
| Repeat protected read after logout | HTTP 401 |

### Live producer verification: November 21, 2026

The updated application ran on port 7829. This check registered a fresh demo
workspace and kept the raw key in PowerShell variables rather than documentation.

| Step | Observed result |
| --- | --- |
| Register demo account and workspace | User and tenant IDs returned |
| Login | HTTP 204 |
| Create producer key through admin session | Key metadata returned |
| Submit event with only Bearer key and idempotency header | Event metadata returned |
| Retry identical request | Event ID comparison returned `True` |
| Revoke key through admin session | HTTP 204 |
| Retry producer request using revoked key | HTTP 401 |

The live check verifies publishing authentication, idempotency reuse, and
revocation. The automated PostgreSQL-backed HTTP tests additionally verify
cross-tenant event routing and durable delivery outbox creation.

## Automated verification

The latest full `mvn -o clean verify` on November 21, 2026 passed **1,327 tests**
with no failures, errors, or skips. This includes producer filter tests, both
security chains, session login/logout, API key generation and hashing, committed
creation/revocation, concurrent revocation, transaction rollback, resource tenant
migrations, cross-tenant routing, and PostgreSQL-backed producer HTTP requests.

The producer HTTP tests verify that a session cannot replace a key; a key cannot
authenticate management routes; only event publishing is permitted; management
CSRF remains enabled; producer requests create no authentication session; and a
forged tenant header cannot change the key's tenant. Filter tests verify null
credentials, `EVENT_PUBLISH` authority, and security-context cleanup on success
and downstream failure. The database-backed tests exercise the real key verifier,
event ingestion, subscriptions, deliveries, and outbox together.

Run the current authentication/producer selection with Docker available:

```powershell
.\mvnw.cmd "-Dtest=ApiKey*Tests,ProducerEvent*Tests,EventIngestionService*Tests,SessionAuthenticationHttpTests" test
```

### Earlier registration and session checkpoints

The session configuration passed 125 affected HTTP/controller tests and a full
`clean verify` run of 948 tests before registration was added. The later
registration selection passed 100 tests: 79 new registration cases plus the 21
existing session cases.

Registration coverage includes ASCII and Unicode password boundaries; actual
Bean Validation discovery of the password getter; request redaction; CSRF and
201/400/409 behavior; password hashing; registration followed by authentication;
case-insensitive duplicate rejection; concurrent registrations producing one
complete account; and rollback after all three INSERTs have reached PostgreSQL.

Run the registration and session selection with Docker available:

```powershell
.\mvnw.cmd "-Dtest=RegistrationPasswordPolicyTests,AccountRegistrationServiceTests,AccountRegistrationServiceIntegrationTests,RegisterAccountRequestTests,AccountRegistrationControllerTests,SessionAuthenticationHttpTests" test
```

The session tests now import both security configurations, fetch real CSRF
tokens through the controller, and verify token refresh, session rotation,
logout cleanup, disabled HTTP Basic, and authentication/CSRF rejection.
Database tests use isolated PostgreSQL containers. The test-scoped
`spring-boot-starter-security-test` dependency connects MockMvc to Spring
Security; the tests do not disable security filters.

Run `.\mvnw.cmd clean verify` for a complete build.

## Milestone 8 checkpoint

Implemented and tested:

- Account registration, encoded passwords, session login/logout, and CSRF handling.
- Tenant membership checks, viewer reads, and administrator management operations.
- Required event/endpoint ownership, legacy backfill, and removal of obsolete unscoped helper queries.
- Tenant-scoped idempotency in PostgreSQL and Redis, plus tenant-scoped delivery routing.
- Tenant/admin checks for subscriptions, secret rotation, and manual delivery replay.
- Generated producer keys with hash-only storage, one-time creation responses, and locked revocation.
- Stateless producer authentication and event ingestion, isolated from management sessions.
- Live creation, publishing, idempotent retry, and revocation verification.

Producer keys grant only `EVENT_PUBLISH`; endpoint management requires an admin
user session. Keys currently have no automatic expiry, and previously issued raw
keys cannot be retrieved. User sessions still use servlet-container storage
rather than shared session storage.

This document records the implementation checkpoint before the milestone review
and commit. The next larger roadmap area is the SvelteKit dashboard.
