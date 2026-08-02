package com.iantapply.orchestra.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable context supplied while evaluating an {@link OrchestraCondition}.
 *
 * @param executionId unique execution being evaluated
 * @param event owning event definition
 * @param stage stage guarded by the condition
 * @param condition condition configuration
 * @param now time captured for this evaluation
 * @param variables immutable snapshot of execution variables
 */
public record ConditionContext(
        UUID executionId,
        EventDefinition event,
        StageDefinition stage,
        ConditionSpec condition,
        Instant now,
        Map<String, Object> variables
) {
    /** Snapshots the variable map for safe asynchronous evaluation. */
    public ConditionContext {
        variables = Map.copyOf(variables);
    }
}
