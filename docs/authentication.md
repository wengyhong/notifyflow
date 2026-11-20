# Account registration and session authentication

NotifyFlow now supports account registration and session-cookie login through
Spring Security. This is the authentication foundation of Milestone 8.

Tenant memberships exist, but endpoint, event, and delivery access is not yet
tenant-scoped. Membership roles are not yet enforced on business operations.
An authenticated user's ability to list endpoints currently proves authentication,
not ownership of those endpoints. Tenant isolation and producer API keys are
the next parts of this milestone.

## Identity model

| Table | Purpose | Important rules |
| --- | --- | --- |
| `tenants` | Organization or workspace | UUID ID, display name, creation time; names need not be unique |
| `app_users` | User identity and stored password hash | Case-insensitive unique email, enabled flag, creation time |
| `tenant_memberships` | A user's role in one tenant | Required user and tenant foreign keys; unique `(tenant_id, user_id)` |

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
which retains the user's UUID for future membership checks. Spring's
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
$baseUrl = "http://localhost:9185"
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
  -UseBasicParsing |
  Select-Object StatusCode
```

Expect 200. This is an authentication check, not a tenant-isolation check.

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
      -UseBasicParsing
} catch {
    [int]$_.Exception.Response.StatusCode
}
```

Expect 401.

### Observed results: November 20, 2026

| Step | Observed result |
| --- | --- |
| Fetch CSRF token | Token metadata returned |
| Register demo account | User and tenant IDs returned |
| Login | HTTP 204 |
| Protected endpoint-list read with session | HTTP 200 |
| Refresh token and log out | HTTP 204 |
| Repeat protected read after logout | HTTP 401 |

## Automated verification

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

The session tests import the actual `SecurityConfiguration`, fetch real CSRF
tokens through the controller, and verify token refresh, session rotation,
logout cleanup, disabled HTTP Basic, and authentication/CSRF rejection.
Database tests use isolated PostgreSQL containers. The test-scoped
`spring-boot-starter-security-test` dependency connects MockMvc to Spring
Security; the tests do not disable security filters.

Run `.\mvnw.cmd verify` for a complete build at the next checkpoint.

## Remaining Milestone 8 work

Existing business data still needs tenant ownership and a migration strategy.
Services and repository queries must scope access to an authorized tenant,
including event routing, endpoint management, delivery reads, replay, and secret
rotation. Redis keys and database idempotency uniqueness must become tenant-aware.
Producer applications also need generated API keys with hashed storage and
tenant-bound authentication. Cross-tenant access and role enforcement require
tests as those features are implemented.
