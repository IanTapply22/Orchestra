# Orchestra

Orchestra is a Paper, Folia, and Velocity plugin suite for durable, multi-stage gameplay and network events. Events are declared in YAML, can recur on cron schedules, target groups of servers, call built-in or custom actions, and recover from backend restarts.

## Requirements

- Java 25
- Paper or Folia 26.2
- Velocity 4.0 when proxy actions are used
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

## Releases and publications

Pushing a semantic version tag such as `v1.2.3` runs the release workflow. It verifies the project, publishes `orchestra-api`, `orchestra-core`, and the combined `orchestra` artifact to GitHub Packages, then creates a GitHub Release containing the plugin JAR, SHA-256 checksum, and CycloneDX SBOM. Maven publications are PGP-signed when the `SIGNING_KEY` and `SIGNING_PASSWORD` secrets are configured; the release JAR also receives a GitHub build-provenance attestation.

Set `API_BASELINE_VERSION` in CI to the latest released API version to make `check` reject incompatible public API changes. See [the dependency verification policy](docs/dependency-verification.md) for checksum maintenance.

## Quick start

1. Build and install the JAR.
2. Start Paper/Folia once to create `plugins/Orchestra/config.yml` and `events/`.
3. Give every backend a unique `server.id` and configure its groups/tags.
4. Enable PostgreSQL, Redis, or the HTTP endpoint when needed.
5. Add event YAML files and restart to validate/load them.

The included `weekend_double_xp.yml` demonstrates a recurring, targeted, multi-stage event.

### Example library

On first startup, Orchestra writes safe, inactive templates to `plugins/Orchestra/examples/`:

- `maintenance_countdown.yml`
- `weekend_multiplier.yml`
- `cross_server_announcement.yml`
- `scheduled_restart.yml`
- `conditional_event_with_retries.yml`

Review a template's schedule, targets, commands, and placeholder secrets, then copy it into `plugins/Orchestra/events/` to enable it. Files in `examples/` are never loaded or executed directly. The scheduled-restart template is intentionally opt-in and assumes an external process supervisor will restart the server.

## How it works

```text
                                      ORCHESTRA

  CONFIGURATION                                                     TRIGGERS

  events/*.yml ------------------+                     +---- Cron scheduler
                                 |                     |
                                 v                     +---- HTTP/API request
                      +---------------------+          |
                      | Schema validation   |          +---- /orchestra command
                      | IDs, fields, paths, |          |
                      | schedules, retries  |          +---- Plugin extension
                      +----------+----------+          |
                                 |                     +---- Startup recovery
                                 v                     |
                      +---------------------+          |
                      | Definition store    |<---------+
                      +----------+----------+
                                 |
                                 v
  config.yml ---------->+---------------------+<---------- orchestra.properties
  environment variables | Orchestrator engine |           environment/secret files
  secret files -------->|                     |<---------- Velocity configuration
                        +----+------------+---+
                             |            |
                 resolve targets          | acquire/renew ownership lease
                             |            |
                             v            v
                  +----------------+   +----------------+
                  | Paper/Folia    |   | Redis lock    |----+
                  | server identity|   | or memory lock|    |
                  | groups + tags  |   +----------------+    |
                  +-------+--------+                           |
                          | eligible                          |
                          v                                   |
                  +----------------+                          |
                  | Execution      |<-------------------------+
                  | state machine  |
                  +-------+--------+
                          |
            +-------------+-------------------+
            |                                 |
            v                                 v
  +---------------------+          +-------------------------+
  | PostgreSQL           |          | Action registry         |
  | executions + audit   |          | built-in + extensions   |
  | or in-memory storage |          +------------+------------+
  +----------+----------+                       |
             ^                       +-----------+-----------+
             |                       |                       |
             |                       v                       v
             |            +--------------------+   +--------------------+
             |            | Paper/Folia action |   | Proxy command      |
             |            | command, message,  |   | publisher          |
             |            | variable, webhook  |   +---------+----------+
             |            +---------+----------+             |
             |                      |                  Redis Pub/Sub
             |                      v                         |
             |              Server / players                 v
             |                                     +--------------------+
             |                                     | Velocity agent     |
             |                                     | route players and  |
             |                                     | enforce proxy state|
             |                                     +---------+----------+
             |                                               |
             +---- persist completion, retry, or next stage -+

  OPERATIONS

  /health + /metrics + audit history <---- runtime state ----> logs + diagnostics
```

Each execution advances through its configured stages. Orchestra persists state before and after work, records completed action keys for idempotency, renews its ownership lease while active, and either advances, retries according to policy, or finishes in a terminal state. After a restart, active durable executions are loaded from PostgreSQL and returned to the engine.

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

Orchestra is licensed under the [GNU Affero General Public License v3.0 or later](LICENSE).
