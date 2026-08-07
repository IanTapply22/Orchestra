# Operations

## Secrets

Use environment variables instead of writing secrets into `config.yml`:

- `ORCHESTRA_POSTGRES_PASSWORD`
- `ORCHESTRA_REDIS_URI`
- `ORCHESTRA_WEB_TOKEN`

The variable names are configurable. Enabling PostgreSQL with an empty or placeholder password fails startup. Enabling the web listener without a token of at least 24 characters also fails startup.

## Observability

Monitor active executions, event transitions, audit failures, active workers, and worker queue size. Persistent queue growth indicates insufficient worker capacity or slow actions. Polling, lease-renewal, listener, scheduler, heartbeat, and Redis reconnect failures are logged without terminating their recurring loops.

## Recovery

Keep node clocks synchronized. Back up PostgreSQL before upgrades containing migrations. Orchestra uses renewable owner-checked leases, but actions remain at-least-once and should be idempotent. Redis Pub/Sub messages sent while a proxy is disconnected are not replayed.

## Verification

Before deploying, run `./gradlew check javadoc jar`. The `check` lifecycle enforces formatting, tests, and minimum line and branch coverage.
