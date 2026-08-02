package com.iantapply.orchestra.api;

import java.util.concurrent.CompletionStage;

/** Extension point for an asynchronous event action. */
@FunctionalInterface
public interface OrchestraAction {
    /**
     * Performs the action without blocking the caller.
     *
     * @param context immutable action context
     * @return stage completed successfully, or exceptionally on failure
     */
    CompletionStage<Void> execute(ActionContext context);
}
