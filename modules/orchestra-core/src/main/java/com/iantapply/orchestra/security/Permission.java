package com.iantapply.orchestra.security;

/** Fine-grained operations that may be granted through a {@link Role}. */
public enum Permission {
    /** Read operational state and metrics. */
    VIEW,
    /** Start, pause, resume, cancel, or retry executions. */
    OPERATE
}
