# Operations

## Secrets

Use environment variables or the corresponding `*-file` settings instead of writing secrets into `config.yml`:

- `ORCHESTRA_POSTGRES_PASSWORD`
- `ORCHESTRA_REDIS_URI`
- `ORCHESTRA_WEB_TOKEN`

Environment variables take precedence over secret files, which take precedence over inline values. The variable names are configurable. Enabling PostgreSQL with an empty or placeholder password fails startup. Enabling the web listener without a token of at least 24 characters also fails startup.

## Observability

Monitor active executions, event transitions, audit failures, action/execution failures, rejected tasks, active workers, and worker queue size. Polling, lease-renewal, listener, scheduler, and Redis reconnect failures have counters and structured exception logs without terminating recurring loops. Persistent queue growth indicates insufficient capacity or actions that do not complete.

## Recovery

Keep node clocks synchronized. Back up PostgreSQL before upgrades containing migrations. Orchestra uses renewable owner-checked leases, but actions remain at-least-once and should be idempotent. Redis Pub/Sub messages sent while a proxy is disconnected are not replayed.

## Verification

Before deploying, run `./gradlew check javadoc jar`. The `check` lifecycle enforces formatting, tests, and minimum line and branch coverage.

## Local server shutdown

`runServer` starts a real Java server process. Gradle exiting or an IDE terminal disconnecting does not guarantee that process has stopped. Use `stop` in the server console and wait for shutdown before cleaning or replacing plugin JARs. On Windows, an orphaned `java` process will retain the JAR handle; identify the process with Task Manager or `Get-Process java` and stop only the confirmed local development server.
