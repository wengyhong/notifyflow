# Local development

## Requirements

- Java 21
- Node.js 24 and npm
- Docker Desktop running with Linux containers

## Start infrastructure

From the repository root, copy the environment template if you do not already
have a `.env` file:

```powershell
Copy-Item .env.example .env
```

Replace the placeholder passwords in `.env` with your local database and RabbitMQ
passwords. Keep `.env` out of Git. For existing data volumes, use the credentials
already configured in those services; editing `.env` does not rotate stored passwords.

Validate the configuration and start PostgreSQL, RabbitMQ, and Redis:

```powershell
docker compose config --quiet
docker compose up -d --wait
docker compose ps
```

`config --quiet` validates without printing the resolved configuration, which
contains passwords. `up -d --wait` starts the services in the background and
waits for their health checks.

All published ports bind to `127.0.0.1`:

| Service | Host port | Container port |
| --- | --- | --- |
| PostgreSQL | 5435 | 5432 |
| RabbitMQ AMQP | 25673 | 5672 |
| RabbitMQ dashboard | 15673 | 15672 |
| Redis | 16379 | 6379 |

The RabbitMQ dashboard is at http://127.0.0.1:15673. The configured username and
virtual host are both `notifyflow`; use your configured RabbitMQ password.

Compose reads `.env` automatically. Spring Boot still needs
`NOTIFYFLOW_DB_PASSWORD` and `NOTIFYFLOW_RABBITMQ_PASSWORD` as environment
variables in its application terminal. The local Spring profile sets Redis's
host port to 16379.

If older standalone containers occupy these ports, stop the NotifyFlow application
and those containers before starting Compose. Keep any old PostgreSQL container
stopped while the Compose container uses the same database volume.

## Start the backend

In a PowerShell terminal at the repository root, set the passwords for the current
process if they are not already set. Enter the same values used for your services
in `.env`; these prompts do not display the passwords or put them in command history:

```powershell
$dbPassword = Read-Host "PostgreSQL password" -AsSecureString
$env:NOTIFYFLOW_DB_PASSWORD = [System.Net.NetworkCredential]::new("", $dbPassword).Password

$rabbitPassword = Read-Host "RabbitMQ password" -AsSecureString
$env:NOTIFYFLOW_RABBITMQ_PASSWORD = [System.Net.NetworkCredential]::new("", $rabbitPassword).Password
```

The Java process receives these values as environment variables. The secure prompt
hides input; it does not make environment variables encrypted storage. New terminals
do not inherit variables set in a different terminal.

Start Spring Boot with the local profile:

```powershell
.\mvnw.cmd spring-boot:run `
    "-Dspring-boot.run.profiles=local" `
    "-Dspring-boot.run.arguments=--server.port=7829"
```

Keep this terminal open. Flyway applies pending migrations automatically; Hibernate
validates the schema. The Maven wrapper supplies the project's Maven version, so
a separate Maven installation is not required.

The backend uses http://localhost:7829. The local profile configures Redis on
16379 and a separate loopback-only management listener on 9091. You do not need
to set `SPRING_DATA_REDIS_PORT` manually. Existing environment overrides take
precedence over properties; remove a stale Redis port override if necessary:

```powershell
Remove-Item Env:SPRING_DATA_REDIS_PORT -ErrorAction SilentlyContinue
```

In another PowerShell terminal, check readiness:

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:9091/actuator/health"
```

Expect status `UP`. See [observability](observability.md) for metrics and delivery
logs. The local profile also permits loopback webhook destinations for local
receiver testing; see [webhook security](webhook-security.md).

## Start the frontend

In a separate PowerShell terminal, from the repository root:

```powershell
Set-Location frontend
npm ci
npm run dev
```

Open the local URL printed by Vite, normally http://localhost:5173. The development
server proxies `/api` requests to http://localhost:7829, so keep the backend on
7829 or update `frontend/vite.config.ts` to match a different backend port.

On a new database, register through the dashboard before logging in. Reusing the
existing PostgreSQL volume keeps the existing accounts, workspaces, endpoints,
and delivery history. There are no shared demo credentials.

The standalone webhook receiver is a separate process, required only when testing
deliveries to a local receiver. Starting Compose and the dashboard does not start it.

## Run checks

From the repository root, with Docker running:

```powershell
.\mvnw.cmd clean verify
```

Backend integration tests provision isolated services with Testcontainers and do
not use the development Compose database.

From `frontend`, install Chromium once after installing npm dependencies, then run
the frontend checks:

```powershell
npx playwright install chromium
npm run check
npm test
npm run build
```

On Linux, use `npx playwright install --with-deps chromium` to install browser
system dependencies as well. Frontend tests use mocked API requests and do not
require the development backend. See the [frontend guide](../frontend/README.md)
for test details.

The GitHub Actions workflow in `.github/workflows/ci.yml` runs backend verification
and frontend checks in independent Ubuntu jobs on pushes and pull requests. It
installs Java 21 and Node.js 24, caches Maven/npm downloads, and installs Chromium
for browser tests. CI does not require your development `.env` or start the
development Compose stack.

## Stop infrastructure

Stop the frontend and backend with Ctrl+C in their respective terminals, then run
this from the repository root:

```powershell
docker compose stop
```

This preserves the containers and their data. Start them again with
`docker compose up -d --wait`.

## Data persistence

PostgreSQL and RabbitMQ use named volumes. Compose reuses existing volumes with
matching names, so starting Compose does not guarantee an empty database.
The actual volume names depend on the Compose project name; with project name
`notifyflow`, they are `notifyflow_postgres_data` and `notifyflow_rabbitmq_data`.

During the guided setup, the standalone PostgreSQL container and the Compose
PostgreSQL container both mounted `notifyflow_postgres_data`. Existing users,
endpoints, and delivery history therefore remained available. Only one of those
containers should run against that volume at a time.

Redis has no named data volume configured here. Treat Redis state as disposable;
see [rate limiting](redis-rate-limiting.md), [endpoint caching](endpoint-caching.md),
and [idempotency caching](idempotency-caching.md) for its application roles.

Avoid `docker compose down -v` when preserving local data: the `-v` option removes
the Compose-managed named volumes. Use `docker compose stop` for routine shutdown.
