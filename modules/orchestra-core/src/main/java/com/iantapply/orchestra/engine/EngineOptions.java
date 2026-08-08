package com.iantapply.orchestra.engine;

import java.time.Duration;

/**
 * Tunable orchestration limits and timing values.
 *
 * @param workerCount maximum concurrent execution workers
 * @param queueCapacity maximum pending execution tasks
 * @param pollInterval delay between durable-work scans
 * @param pollBatchSize maximum executions loaded by one scan
 * @param leaseDuration renewable execution lease lifetime
 * @param shutdownTimeout graceful worker shutdown allowance
 */
public record EngineOptions(
        int workerCount,
        int queueCapacity,
        Duration pollInterval,
        int pollBatchSize,
        Duration leaseDuration,
        Duration shutdownTimeout) {
    /** Validates that every capacity and duration is positive. */
    public EngineOptions {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        if (pollBatchSize < 1) throw new IllegalArgumentException("pollBatchSize must be positive");
        requirePositive("pollInterval", pollInterval);
        requirePositive("leaseDuration", leaseDuration);
        requirePositive("shutdownTimeout", shutdownTimeout);
    }

    /**
     * Returns production defaults with the supplied worker and queue bounds.
     *
     * @param workerCount maximum concurrent workers
     * @param queueCapacity maximum pending tasks
     * @return validated production defaults
     */
    public static EngineOptions defaults(int workerCount, int queueCapacity) {
        return new EngineOptions(
                workerCount,
                queueCapacity,
                Duration.ofMillis(250),
                256,
                Duration.ofMinutes(10),
                Duration.ofSeconds(10));
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
