# Extensions

Other plugins can obtain the enabled Paper engine through `OrchestraPlugin.engine()` and register lifecycle listeners, schedule definitions, or call execution controls. Public extension contracts live in `orchestra-api`; depend on that module instead of platform or adapter internals.

## Actions and conditions

Register actions and conditions through `ActionRegistry`. Implementations return `CompletionStage` values and must not block the server thread. External actions should use `ActionContext.idempotencyKey()` when the destination supports deduplication.

Built-in actions:

- `broadcast`, `title`, and `action_bar` accept a MiniMessage `message`.
- `command` accepts `execute` and runs as console.
- `set_variable` accepts `key` and optional `value`; omission removes the key.
- `toggle_joins` accepts `value`.
- `discord_webhook` sends an HTTP webhook.
- `move_player` accepts `proxy`, `player`, and `server` and requires Redis/Velocity.
- `toggle_group_joins` accepts `proxy`, `group`, and `enabled` and requires Redis/Velocity.

Built-in conditions:

- `online_players_at_least` accepts `count`.
- `variable_equals` accepts `key` and `value`.

Message and command strings can interpolate `{event_id}`, `{execution_id}`, and `{server}`.

## Compatibility

Treat public Java types, action/condition names, YAML fields, persisted values, and HTTP routes as compatibility surfaces. The PostgreSQL binary value codec carries a format version, reads the previous format, bounds blobs/collections/strings, and rejects unknown future versions.
