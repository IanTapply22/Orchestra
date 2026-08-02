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
./gradlew clean test jar javadoc
```

The plugin JAR is written to `build/libs`. Copy that same JAR into the `plugins` directory of every Paper/Folia backend and every Velocity proxy that should participate. The JAR contains both `paper-plugin.yml` and `velocity-plugin.json`; each platform loads only its own entry point.

Paper resolves HikariCP and the PostgreSQL JDBC driver through its plugin classpath loader. Lombok is compile-time only and is not included in the finished JAR. If IntelliJ reports unresolved generated constructors, enable annotation processing and reload the Gradle project.

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
 AuditRepository callers --------------> audit repository
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

Built-in Paper actions are `broadcast`, `title`, `action_bar`, `command`, `toggle_joins`, `discord_webhook`, and the engine-local `set_variable` action. Built-in conditions are `online_players_at_least` and `variable_equals`. MiniMessage formatting is supported by message actions.

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

postgres:
  enabled: false
  jdbc-url: "jdbc:postgresql://localhost:5432/orchestra"
  username: "orchestra"
  password: "change-me"
  maximum-pool-size: 8

redis:
  enabled: false
  uri: "redis://localhost:6379/0"
  namespace: "orchestra"

web:
  enabled: false
  bind: "127.0.0.1"
  port: 8787
  tokens:
    "replace-with-a-long-random-token": ADMINISTRATOR
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

Authenticated requests use `Authorization: Bearer <token>`. Token values map to `VIEWER`, `OPERATOR`, `APPROVER`, or `ADMINISTRATOR` roles in `web.tokens`. The current build does not expose event mutation endpoints or a browser dashboard.

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

Generated API documentation is available after `./gradlew javadoc` in `build/docs/javadoc`.

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
- A lease is intentionally short-lived; long custom actions should be idempotent because another node may recover work after lease expiry.
