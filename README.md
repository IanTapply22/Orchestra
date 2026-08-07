# Orchestra

Orchestra is a Paper, Folia, and Velocity plugin suite for defining scheduled gameplay and network operations as durable, multi-stage events. A single build artifact contains both the Paper/Folia backend plugin and the Velocity proxy plugin.

## Requirements

- Java 25
- Paper or Folia 26.2 for backend servers
- Velocity 3.4 for proxy integration
- PostgreSQL when durable execution recovery is required
- Redis when more than one coordinator can process the same schedules or executions

PostgreSQL, Redis, and the HTTP listener are optional and disabled by default. Without them, Orchestra uses in-memory execution state and process-local leases; that mode is useful for development and a single server, but it cannot recover state after a process restart.

## Build and installation

Build and test the project with the included Gradle wrapper:

```shell
./gradlew clean lint test jar javadoc
```

The plugin JAR is written to `build/libs`. Copy that same JAR into the `plugins` directory of every Paper/Folia backend and every Velocity proxy that should participate. The JAR contains both `paper-plugin.yml` and `velocity-plugin.json`; each platform loads only its own entry point.

Paper resolves HikariCP and the PostgreSQL JDBC driver through its plugin classpath loader. Their versions, the compile-time dependencies, and test dependencies are centralized in `gradle/libs.versions.toml`; Gradle lock files make resolution repeatable.

### Formatting and linting

Run the formatter check without changing files:

```shell
./gradlew lint
```

Apply formatting fixes with either `./gradlew lintFix` or `./gradlew spotlessApply`. Formatting covers Java sources and tests, Gradle Kotlin scripts, Markdown, YAML, properties, SQL, and repository shell hooks.

Install the tracked pre-commit hook once for the current Git checkout:

```shell
./gradlew installGitHooks
```

The hook runs `lint` before each commit and blocks the commit when formatting violations exist. It never modifies or stages files automatically.

### Tests and coverage

`./gradlew test` runs the unit and local integration suite and automatically generates a JaCoCo report at `build/reports/jacoco/test/html/index.html`. The suite covers domain validation, engine lifecycle and recovery, retries and idempotency, YAML and cron parsing, in-memory and JDBC repositories, migrations, Redis RESP/leases/Pub/Sub, HTTP authentication, Velocity commands, Paper target selection, Discord webhooks, audit history, metrics, and RBAC.

Paper/Folia and Velocity lifecycle bootstraps still require smoke testing on their real server runtimes; the automated suite tests the platform-neutral services and adapters those entry points assemble.

## Local development

Import the project into IntelliJ as a Gradle project and use the included wrapper for all commands. Orchestra targets Java 25, so configure the project SDK and Gradle JVM to a Java 25 installation.

Useful development commands:

```shell
./gradlew clean lint test jar
./gradlew lintFix
./gradlew javadoc
./gradlew publishToMavenLocal
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

### Running a local Paper server

The project includes the run-paper Gradle plugin for quick backend testing. Start a disposable local Paper server with:

```shell
./gradlew runServer
```

The task builds Orchestra, downloads Paper 26.2, creates a local server directory under `run`, installs the current plugin build, accepts the Mojang EULA for that development server, and starts Minecraft on the normal local server port. Stop it from the server console with:

```text
stop
```

Debug from IntelliJ with:

```shell
./gradlew runServer --debug-jvm
```

Then attach a Remote JVM Debug configuration to `localhost:5005`. The JVM waits for the debugger before continuing.

`runServer` is for Paper development only. It does not launch Folia or Velocity. For Folia or Velocity smoke tests, build the JAR and copy the same artifact from `build/libs` into that server's `plugins` directory.

### Local configuration

On first startup, the local server creates `run/plugins/Orchestra/config.yml` and copies the example event into `run/plugins/Orchestra/events`. The default configuration keeps PostgreSQL, Redis, and the HTTP listener disabled, which is the easiest mode for engine and action development.

Enable PostgreSQL locally when testing restart recovery, optimistic locking, migrations, or audit persistence. Enable Redis locally when testing multi-process scheduling, distributed leases, proxy transport, or Velocity routing. Keep each local Paper/Folia server on a unique `server.id`, even when they are only test servers.

### Local verification loop

Before opening a pull request or publishing a build, run:

```shell
./gradlew clean lint test jar javadoc
```

Use `./gradlew installGitHooks` once per checkout if you want formatting checked before every commit. The hook runs `lint` and does not modify files automatically.

## Quick start

1. Build the JAR and install it on a Paper or Folia server.
2. Start the server once to create `plugins/Orchestra/config.yml` and the `events` directory.
3. Edit the server identity and optional infrastructure settings.
4. Add or edit event YAML files in `plugins/Orchestra/events`.
5. Restart the server to validate and load the definitions.

On first startup Orchestra copies `weekend_double_xp.yml` into the events directory. Existing event files are never overwritten.

## Data flow

```text
                                      +-----------------------+
 Event YAML files ------------------->| Definition repository |
                                      +-----------+-----------+
                                                  |
 Cron scheduler ----------------------+           | lookup
                                      |           v
 API / plugin start request ----------+-->+--------------------+
                                          | OrchestratorEngine |
                                          +----+----------+----+
                                               |          |
                                  acquire lease|          | create / compare-and-set
                                               v          v
                                        +-------------+  +----------------------+
                                        | Redis lease |  | Execution repository |
                                        | or local    |  | PostgreSQL or memory |
                                        +------+------+  +----------+-----------+
                                               |                    |
                                               | owns execution     | recovery scan
                                               v                    |
                                      +-------------------+         |
                                      | StageExecutor     |<--------+
                                      +----+---------+----+
                                           |         |
                              resolve target         | persist action key / variables
                                           v         v
                                  +------------------------+
                                  | ActionRegistry         |
                                  +------+-----------------+
                                         |
                  +----------------------+----------------------+
                  |                                             |
                  v                                             v
       Paper/Folia global scheduler                    Discord HTTP webhook
       commands, messages, join gate

 Velocity proxy <---- Redis Pub/Sub ---- VelocityAgent ----> player routing / heartbeats

 Lifecycle listeners ----> metrics ----> authenticated /metrics endpoint
 Authenticated operator actions -------> audit repository
```

The engine uses at-least-once execution semantics. Before an external action runs, it receives an idempotency key composed from the execution, stage, action, and target server. Custom actions should pass that key to external systems that support deduplication. Completed action keys and execution variables are persisted with the execution.

## Event definitions

Definitions use five-field cron expressions and ordered stages. Durations accept compact values such as `500ms`, `30s`, `5m`, `2h`, and `2d`, as well as ISO-8601 durations.

```yaml
id: weekend_double_xp
display-name: "Double XP Weekend"

schedule:
  cron: "0 18 * * FRI"
  timezone: "America/Toronto"

targets:
  groups: [survival]

stages:
  - id: announcement
    duration: 5m
    actions:
      - broadcast: "<gold>Double XP begins in 5 minutes!"

  - id: active
    duration: 48h
    actions:
      - type: set_variable
        key: xp_multiplier
        value: 2
      - command: "quests multiplier 2"

  - id: cleanup
    actions:
      - type: set_variable
        key: xp_multiplier
        value: 1
      - broadcast: "<yellow>Double XP has ended."
```

An event ID must be 2-64 lowercase letters, numbers, underscores, or hyphens. Stage IDs must be unique within an event. A target can select explicit server IDs, groups, tags, or every online server:

```yaml
targets:
  servers: [survival-1]
  groups: [survival]
  tags:
    region: na-east
  all-online: false
```

Supported action forms are either shorthand or explicit:

```yaml
actions:
  - broadcast: "<gold>Hello!</gold>"
  - id: enable_bonus
    type: set_variable
    key: xp_multiplier
    value: 2
    retry:
      max-attempts: 3
      initial-delay: 1s
      multiplier: 2
      maximum-delay: 30s
```

Built-in Paper actions are `broadcast`, `title`, `action_bar`, `command`, `toggle_joins`, `discord_webhook`, `move_player`, `toggle_group_joins`, and the engine-local `set_variable` action. Proxy actions require Redis and accept a `proxy` argument identifying the Velocity agent. Built-in conditions are `online_players_at_least` and `variable_equals`. MiniMessage formatting is supported by message actions.

## Configuration

The default Paper/Folia configuration is:

```yaml
server:
  id: "survival-1"
  groups: ["survival"]
  tags:
    game: "survival"
    region: "na-east"

engine:
  workers: 4
  queue-capacity: 256
  poll-interval-ms: 250
  poll-batch-size: 256
  lease-seconds: 600
  shutdown-seconds: 10

postgres:
  enabled: false
  jdbc-url: "jdbc:postgresql://localhost:5432/orchestra"
  username: "orchestra"
  password: ""
  password-environment-variable: "ORCHESTRA_POSTGRES_PASSWORD"
  maximum-pool-size: 8

redis:
  enabled: false
  uri: "redis://localhost:6379/0"
  uri-environment-variable: "ORCHESTRA_REDIS_URI"
  namespace: "orchestra"

web:
  enabled: false
  bind: "127.0.0.1"
  port: 8787
  token-environment-variable: "ORCHESTRA_WEB_TOKEN"
  tokens: {}
```

Give every backend a unique `server.id`. Groups and tags are matched by the local Paper target resolver. Worker and queue limits bound CPU concurrency and retained tasks. Size the PostgreSQL pool below the database connection limit after accounting for every backend process.

### Velocity configuration

The Velocity agent is configured with JVM system properties:

```text
-Dorchestra.redis.uri=redis://user:password@redis:6379/0
-Dorchestra.redis.namespace=orchestra
-Dorchestra.proxy.id=velocity-1
```

Give every proxy a unique ID. The agent publishes a heartbeat every five seconds and accepts tab-delimited `MOVE` and `JOINS` messages on its namespaced proxy channel. Backend agents remain authoritative for enforcing their local join gate.

## Persistence and recovery

When PostgreSQL is enabled, startup applies the bundled idempotent migration before recovery begins. The execution repository stores state, stage deadlines, variables, completed action idempotency keys, and optimistic-lock versions. The audit repository can store append-only operational records for integrations; the built-in health and metrics endpoints do not create audit entries. Event definitions remain YAML-backed and are reloaded into memory during startup.

On startup the engine scans active executions. Work left in `STARTING` or `RUNNING` can be submitted again, so external actions must be idempotent. Each write uses a version-checked compare-and-set; concurrent coordinators cannot silently overwrite one another.

## Redis coordination

Distributed leases use Redis `SET NX PX` with a random owner token. Release uses a Lua compare-and-delete operation, preventing an expired owner from deleting a newer owner's lease. Cron occurrences also acquire a lease, so multiple coordinators do not intentionally schedule the same minute-level occurrence.

Redis Pub/Sub subscriptions reconnect after failures. Pub/Sub itself is not durable: commands published while a proxy is disconnected are not replayed. Run Redis on a private network with authentication and an appropriate persistence policy.

## HTTP and Prometheus

The embedded HTTP listener currently exposes:

- `GET /health` - unauthenticated process health
- `GET /metrics` - Prometheus text exposition requiring the `VIEW` permission
- `POST /events/{eventId}/executions` - immediately trigger a loaded event definition, requiring the `OPERATE` permission

Authenticated requests use `Authorization: Bearer <token>`. Token values map to `VIEWER`, `OPERATOR`, or `ADMINISTRATOR` roles in `web.tokens`. Tokens must contain at least 24 characters; `ORCHESTRA_WEB_TOKEN` supplies an administrator token without storing it in YAML. Successful event starts create audit records. The current build does not expose event mutation endpoints or a browser dashboard.

Trigger an event with an empty request body:

```shell
curl -X POST \
  -H "Authorization: Bearer <token>" \
  http://127.0.0.1:8787/events/weekend_double_xp/executions
```

A successful request returns `202 Accepted` and the new execution identifier:

```json
{"execution_id":"0dc1e66c-5537-437b-8fd5-96f1e9dabf1c","definition_id":"weekend_double_xp"}
```

Bind the listener to a private interface. Do not expose it directly to the public internet; use a long random bearer token and terminate TLS at a trusted reverse proxy.

## Paper and Folia threading

Built-in Bukkit operations execute through the global-region scheduler and are safe to invoke from engine workers on Paper and Folia. Custom actions that touch an entity, chunk, or location are responsible for scheduling that work through the corresponding entity or region scheduler.

## Java extension API

Register custom asynchronous actions and conditions in an `ActionRegistry`:

```java
registry.registerAction("spawn_boss", context -> bossService.spawn(
        context.server(),
        context.getString("boss"),
        context.getString("location"),
        context.idempotencyKey()
));

registry.registerCondition("feature_enabled", context ->
        featureService.isEnabled(context.condition().arguments().get("key"))
);
```

Implementations return `CompletionStage` values. They should avoid blocking the engine worker, honor the stage timeout where possible, and use `ActionContext.idempotencyKey()` when producing external side effects. Lifecycle listeners are called after a status transition is successfully persisted; one failing listener does not prevent later listeners from running.

The generated API documentation is published at [orchestra.iantapply.com/javadoc](https://orchestra.iantapply.com/javadoc/). To build it locally, run `./gradlew javadoc`; the output is written to `build/docs/javadoc`.

## Source layout

```text
src/main/java/com/iantapply/orchestra/
|-- api/                    public extension API and definitions
|-- domain/                 persisted runtime state
|-- engine/                 state machine, retries, and stage execution
|-- port/                   storage, lease, transport, and discovery contracts
|-- adapter/
|   |-- memory/             thread-safe local adapters
|   |-- postgres/           migrations and durable repositories
|   |-- redis/              RESP transport and distributed leases
|   `-- yaml/               event definition parsing
|-- audit/                  operational audit records
|-- metrics/                Prometheus metric registry
|-- schedule/               cron parsing and recurring dispatch
|-- security/               actors, roles, and permissions
|-- velocity/               transport-facing proxy agent
|-- web/                    health and metrics HTTP server
`-- platform/
    |-- paper/              Paper/Folia lifecycle and integrations
    |   `-- action/         built-in actions and conditions
    `-- velocity/           Velocity plugin bootstrap
```

Dependencies point inward: platform and infrastructure adapters depend on the engine and its ports, while the engine has no Bukkit or Velocity dependency. This keeps orchestration behavior testable and allows infrastructure implementations to change without changing the state machine.

## Operational notes

- Keep clocks synchronized across nodes because schedules, due times, and lease expirations are time-sensitive.
- Monitor `orchestra_active_executions` and `orchestra_event_transitions_total`.
- Back up PostgreSQL before changing plugin versions that introduce migrations.
- Test event YAML and custom actions on a staging network before production use.
- Treat Redis and PostgreSQL credentials, webhook URLs, and HTTP bearer tokens as secrets.
- Execution leases renew while work is active. Actions must remain idempotent because a process can fail after an external side effect and before persisting its completion key.

Further design and deployment guidance lives in [`docs/architecture.md`](docs/architecture.md) and [`docs/operations.md`](docs/operations.md). See [`CONTRIBUTING.md`](CONTRIBUTING.md) for repository quality gates and [`SECURITY.md`](SECURITY.md) for private vulnerability reporting and secret-management guidance.
