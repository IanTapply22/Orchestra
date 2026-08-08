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
        /**
         * Extends this lease only if it is still owned by this instance.
         *
         * @param duration new lifetime measured from the renewal
         * @return whether ownership was retained and extended
         */
        boolean renew(Duration duration);

        /** Releases this lease; repeated calls must be harmless. */
        @Override
        void close();
    }
}
