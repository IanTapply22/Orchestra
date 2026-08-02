package com.iantapply.orchestra.api;

import com.iantapply.orchestra.domain.EventExecution;

/** Observes successfully persisted event status transitions. */
@FunctionalInterface
public interface EventLifecycleListener {
    /**
     * Handles a transition after the updated execution has been persisted.
     *
     * @param before execution before the transition
     * @param after persisted execution after the transition
     */
    void onTransition(EventExecution before, EventExecution after);
}
