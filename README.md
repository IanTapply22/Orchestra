# Orchestra

Orchestra is a Paper, Folia, and Velocity plugin suite for durable, multi-stage gameplay and network events. Events are declared in YAML, can recur on cron schedules, target groups of servers, call built-in or custom actions, and recover from backend restarts.

## Requirements

- Java 25
- Paper or Folia 26.2
- Velocity 3.4 when proxy actions are used
- PostgreSQL for durable executions and audit history (optional)
- Redis for distributed leases and proxy messaging (optional)

With PostgreSQL and Redis disabled, Orchestra runs in a single-process development mode with in-memory state.

## Build

Use the checked-in wrapper:

```shell
./gradlew check javadoc jar
```

On Windows, use `.\gradlew.bat`. The combined Paper/Folia and Velocity artifact is written to `build/libs/Orchestra-<version>.jar`. Install the same JAR on participating backend servers and proxies.

Useful tasks:

```shell
./gradlew lint             # verify formatting
./gradlew lintFix          # apply formatting
./gradlew test             # unit and local adapter tests
./gradlew integrationTest  # real PostgreSQL/Redis tests; skips without Docker
./gradlew runServer        # disposable local Paper server
```

## Quick start

1. Build and install the JAR.
2. Start Paper/Folia once to create `plugins/Orchestra/config.yml` and `events/`.
3. Give every backend a unique `server.id` and configure its groups/tags.
4. Enable PostgreSQL, Redis, or the HTTP endpoint when needed.
5. Add event YAML files and restart to validate/load them.

The included `weekend_double_xp.yml` demonstrates a recurring, targeted, multi-stage event.

## Modules

| Module | Responsibility |
| --- | --- |
| `orchestra-api` | Public extension contracts, immutable definitions, domain state, and ports |
| `orchestra-core` | Engine, scheduling, audit, metrics, security, HTTP, and proxy-neutral services |
| `orchestra-adapter-*` | In-memory, PostgreSQL, and Redis implementations |
| `orchestra-platform-paper` | Paper/Folia bootstrap, YAML loading, and server actions |
| `orchestra-platform-velocity` | Velocity bootstrap and proxy command agent |
| `orchestra-distribution` | Combined artifact, publication, local server, and infrastructure tests |

## Operational model

Executions use optimistic versions and renewable owner-checked leases. Completed action keys prevent ordinary replay, but the external side effect and its completion write cannot be atomic: actions are therefore **at-least-once** and should pass `ActionContext.idempotencyKey()` to systems that support deduplication.

Redis Pub/Sub is transient. PostgreSQL is the durable source of execution and audit state. Keep clocks synchronized, protect infrastructure with authentication, and back up PostgreSQL before migration-bearing upgrades.
