# Orchestra developer API

This module contains the stable contracts used by plugins that create, start, observe, or extend Orchestra events. It has no Paper dependency. The installed Orchestra plugin publishes an `OrchestraService` through Paper's service manager; consuming plugins should compile against `orchestra-api` and must not shade it into their JAR.

## Add the dependency

Orchestra publishes packages to GitHub Packages. Replace `<version>` with the installed Orchestra version.

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/iantapply22/orchestra")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull
            password = providers.environmentVariable("GITHUB_TOKEN").orNull
        }
    }
}

dependencies {
    compileOnly("com.iantapply:orchestra-api:<version>")
}
```

Declare the installed plugin as a required runtime dependency. `join-classpath` lets your Paper plugin use the API classes supplied by Orchestra at runtime.

```yaml
# paper-plugin.yml
dependencies:
  server:
    Orchestra:
      load: BEFORE
      required: true
      join-classpath: true
```

## Obtain the service

Request the service during your plugin's `onEnable`. The declared dependency guarantees Orchestra has enabled first.

```java
import com.iantapply.orchestra.api.OrchestraService;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {
    private OrchestraService orchestra;

    @Override
    public void onEnable() {
        orchestra = Objects.requireNonNull(
                Bukkit.getServicesManager().load(OrchestraService.class),
                "Orchestra service is unavailable");
    }
}
```

Do not construct `OrchestraPlugin` or `OrchestratorEngine` yourself when integrating with an installed server plugin. The service is connected to the configured PostgreSQL, Redis, scheduler, targets, actions, and recovery process.

## Start or schedule a YAML event

An event must already exist in `plugins/Orchestra/events/` or have been registered through the API. Its YAML `id` is the identifier used below.

```java
UUID executionId = orchestra.startNow("weekend_double_xp");
```

Schedule it for later and provide initial variables:

```java
Instant startAt = Instant.now().plus(Duration.ofMinutes(10));
UUID executionId = orchestra.schedule(
        "weekend_double_xp",
        startAt,
        Map.of("requested_by", "ExamplePlugin", "multiplier", 2));
```

`startNow` and `schedule` throw `IllegalArgumentException` when the definition ID is unknown. The returned UUID is the durable execution ID, not the event-definition ID.

## Observe and control executions

Lifecycle listeners run after a state transition has been persisted. Keep listener work short; hand expensive work to your own asynchronous executor.

```java
orchestra.addListener((before, after) -> getLogger().info(
        "%s changed from %s to %s"
                .formatted(after.id(), before.status(), after.status())));
```

Read a current immutable snapshot:

```java
orchestra.execution(executionId).ifPresent(execution -> {
    EventStatus status = execution.status();
    int currentStage = execution.stageIndex();
    Map<String, Object> variables = execution.variables();
});
```

List current work or obtain a consistently formatted operational snapshot:

```java
orchestra.activeExecutions(100).forEach(execution ->
        getLogger().info(execution.id() + " " + execution.status()));

OrchestraStatus status = orchestra.status();
getLogger().info(status.summary());
```

Control methods return `false` if the execution does not exist or its optimistic update loses a race. An illegal state transition throws `IllegalStateException`.

```java
orchestra.pause(executionId);
orchestra.resume(executionId);
orchestra.setVariable(executionId, "multiplier", 3);
orchestra.setVariable(executionId, "temporary_value", null); // remove
orchestra.cancel(executionId);

// Valid for a FAILED execution; restarts at the first stage.
orchestra.retry(executionId);
```

## Register an event in Java

Programmatic definitions use the same validation and execution engine as YAML definitions. IDs must be stable lowercase identifiers because persisted executions refer to them.

```java
RetryPolicy retry = new RetryPolicy(
        3,
        Duration.ofSeconds(1),
        2.0,
        Duration.ofSeconds(10));

ActionSpec announcement = new ActionSpec(
        "announce",
        "broadcast",
        Map.of("message", "<green>A developer event has started!"),
        retry);

StageDefinition stage = new StageDefinition(
        "opening",
        Duration.ZERO,
        Duration.ofSeconds(15),
        List.of(),
        List.of(announcement));

EventDefinition definition = new EventDefinition(
        "developer_event",
        "Developer Event",
        TargetSelector.ALL_ONLINE,
        List.of(stage));

orchestra.registerDefinition(definition);
UUID executionId = orchestra.startNow(definition.id());
```

Registering the same definition ID replaces the definition used by future processing. Do not change or remove stages while executions of that definition are active.

## Register a custom action

Actions return a `CompletionStage<Void>` and must not block the server thread. Throw an exception or complete exceptionally to activate the action's configured retry policy.

```java
orchestra.registerAction("grant_tokens", context -> {
    String account = context.getString("account");
    int amount = Integer.parseInt(context.getString("amount"));

    // Pass this key to the external system so a retried action is not applied twice.
    String idempotencyKey = context.idempotencyKey();
    return tokenClient.grant(account, amount, idempotencyKey);
});
```

Use it from YAML:

```yaml
actions:
  - id: daily_tokens
    type: grant_tokens
    account: network
    amount: 100
    retry:
      max-attempts: 4
      initial-delay: 1s
      multiplier: 2
      maximum-delay: 30s
```

Action type names are case-insensitive after normalization and must be unique. Register custom types during your plugin's `onEnable`, before starting definitions that reference them.

## Register a custom condition

Conditions are asynchronous and return whether a stage may run.

```java
orchestra.registerCondition("feature_enabled", context -> {
    String feature = String.valueOf(context.condition().arguments().get("feature"));
    return CompletableFuture.completedFuture(featureFlags.isEnabled(feature));
});
```

```yaml
conditions:
  - type: feature_enabled
    feature: seasonal_rewards
```

A false condition fails that execution with a stage-specific message. Exceptions and timeouts also fail the attempt.

## Built-in actions

| Type | Arguments | Notes |
| --- | --- | --- |
| `broadcast` | `message` | Sends a MiniMessage message to the server. |
| `title` | `message` | Shows a title to online players. |
| `action_bar` | `message` | Sends an action-bar message. |
| `command` | `execute` | Runs a console command. |
| `set_variable` | `key`, optional `value` | Omitting `value` removes the variable. |
| `toggle_joins` | `value` | Controls joins on the local server. |
| `discord_webhook` | `url`, `message` | Sends an asynchronous webhook request. |
| `move_player` | `proxy`, `player`, `server` | Requires Redis and the Velocity agent. |
| `toggle_group_joins` | `proxy`, `group`, `enabled` | Requires Redis and the Velocity agent. |

Messages and commands can interpolate `{event_id}`, `{execution_id}`, and `{server}`.

## Built-in conditions

| Type | Arguments | Meaning |
| --- | --- | --- |
| `online_players_at_least` | `count` | Passes when the local server has at least that many players. |
| `variable_equals` | `key`, `value` | Compares an execution variable with the configured value. |

## Trigger through HTTP

When Orchestra's web listener is enabled, non-Java services can start definitions without using the Java API:

```shell
curl -X POST \
  -H "Authorization: Bearer $ORCHESTRA_TOKEN" \
  http://127.0.0.1:8787/events/weekend_double_xp/executions
```

The token requires the `OPERATE` permission. A successful request returns HTTP `202` with the new `execution_id`. `GET /health` is public, while `GET /metrics` requires a token with `VIEW` permission.

## Reliability rules

- Definitions and execution snapshots are immutable after construction.
- PostgreSQL provides durable executions and recovery; in-memory mode does not survive a restart.
- Redis leases prevent multiple servers from owning the same execution concurrently.
- Actions are at-least-once around external side effects. Use `ActionContext.idempotencyKey()` whenever possible.
- Redis Pub/Sub is transient; proxy commands sent during a disconnection are not replayed.
- Never block an action or condition future on the Paper server thread.

See the generated Javadocs for individual validation rules and [the extension overview](../../docs/extensions.md) for compatibility guidance.
