package com.iantapply.orchestra.api;

import java.util.Map;

/**
 * Declarative configuration for a stage condition.
 *
 * @param type registered condition type
 * @param arguments immutable condition-specific arguments
 */
public record ConditionSpec(String type, Map<String, Object> arguments) {
    /** Validates the type and snapshots arguments. */
    public ConditionSpec {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Condition type is required");
        arguments = Map.copyOf(arguments);
    }
}
