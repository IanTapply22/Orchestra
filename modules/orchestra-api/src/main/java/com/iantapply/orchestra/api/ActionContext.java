package com.iantapply.orchestra.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable context supplied to an {@link OrchestraAction} invocation.
 *
 * @param executionId unique execution being processed
 * @param event event definition that owns the action
 * @param stage stage containing the action
 * @param action action configuration
 * @param server resolved target server
 * @param now time captured for this invocation
 * @param variables immutable snapshot of execution variables
 */
public record ActionContext(
        UUID executionId,
        EventDefinition event,
        StageDefinition stage,
        ActionSpec action,
        String server,
        Instant now,
        Map<String, Object> variables) {
    /** Validates required values and snapshots the variable map. */
    public ActionContext {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(event);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(action);
        Objects.requireNonNull(server);
        Objects.requireNonNull(now);
        variables = Map.copyOf(variables);
    }

    /**
     * Returns a stable key suitable for deduplicating external side effects.
     *
     * @return execution, stage, action, and server identity joined into one key
     */
    public String idempotencyKey() {
        return executionId + ":" + stage.id() + ":" + action.id() + ":" + server;
    }

    /**
     * Reads a required action argument as text.
     *
     * @param key argument name
     * @return argument value converted to a string
     * @throws IllegalArgumentException when the argument is absent
     */
    public String getString(String key) {
        Object value = action.arguments().get(key);
        if (value == null) throw new IllegalArgumentException("Missing action argument: " + key);
        return value.toString();
    }
}
