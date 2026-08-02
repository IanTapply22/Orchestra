package com.iantapply.orchestra.api;

import java.time.Duration;
import java.util.List;

/**
 * One ordered stage in an event workflow.
 *
 * @param id identifier unique within its event
 * @param duration delay before the next stage becomes due
 * @param timeout maximum time allowed for each condition or action attempt
 * @param conditions conditions that must all pass
 * @param actions actions executed for each resolved target
 */
public record StageDefinition(
        String id,
        Duration duration,
        Duration timeout,
        List<ConditionSpec> conditions,
        List<ActionSpec> actions
) {
    /** Validates timing values and snapshots conditions and actions. */
    public StageDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Stage id is required");
        duration = duration == null ? Duration.ZERO : duration;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        if (duration.isNegative()) throw new IllegalArgumentException("Stage duration cannot be negative");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Stage timeout must be positive");
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }
}
