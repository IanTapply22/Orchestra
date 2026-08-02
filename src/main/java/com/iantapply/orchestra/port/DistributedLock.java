package com.iantapply.orchestra.port;

import java.time.Duration;
import java.util.Optional;

/** Provides short-lived, owner-safe leases shared by orchestrator nodes. */
public interface DistributedLock {
    /**
     * Attempts to claim a key for a bounded duration without waiting.
     *
     * @param key globally meaningful lock key
     * @param duration lease lifetime
     * @return owned lease, or empty when another node owns the key
     */
    Optional<Lease> tryAcquire(String key, Duration duration);

    /** A lock claim that releases only its own ownership token when closed. */
    interface Lease extends AutoCloseable {
        /** Releases this lease; repeated calls must be harmless. */
        @Override void close();
    }
}
