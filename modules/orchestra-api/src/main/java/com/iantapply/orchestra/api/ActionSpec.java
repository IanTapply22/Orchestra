package com.iantapply.orchestra.api;

import java.util.Map;

/**
 * Declarative configuration for one stage action.
 *
 * @param id action identifier unique within its stage
 * @param type registered action type
 * @param arguments immutable action-specific arguments
 * @param retryPolicy retry policy, or {@code null} to use {@link RetryPolicy#DEFAULT}
 */
public record ActionSpec(String id, String type, Map<String, Object> arguments, RetryPolicy retryPolicy) {
    /** Validates identifiers and snapshots arguments. */
    public ActionSpec {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Action id is required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Action type is required");
        arguments = Map.copyOf(arguments);
        retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
    }
}
