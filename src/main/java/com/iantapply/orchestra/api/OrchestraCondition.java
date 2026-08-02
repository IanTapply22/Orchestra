package com.iantapply.orchestra.api;

import java.util.concurrent.CompletionStage;

/** Extension point for an asynchronous stage condition. */
@FunctionalInterface
public interface OrchestraCondition {
    /**
     * Evaluates whether a stage may execute.
     *
     * @param context immutable condition context
     * @return stage producing {@code true} when execution may continue
     */
    CompletionStage<Boolean> test(ConditionContext context);
}
