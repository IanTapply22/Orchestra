package com.iantapply.orchestra.api;

/** Lifecycle states persisted for an event execution. */
public enum EventStatus {
    /** Definition exists but cannot execute. */
    DRAFT,
    /** Execution is waiting for its due time. */
    SCHEDULED,
    /** First stage is being prepared. */
    STARTING,
    /** One or more stages have run and further work may be due. */
    RUNNING,
    /** Execution is suspended until explicitly resumed. */
    PAUSED,
    /** Every stage completed successfully. */
    COMPLETED,
    /** Execution stopped because a condition or action failed. */
    FAILED,
    /** Execution was explicitly terminated. */
    CANCELLED
}
