package com.iantapply.orchestra.api;

import java.time.Duration;

/**
 * Bounded exponential-backoff policy for action attempts.
 *
 * @param maxAttempts total number of attempts, including the first
 * @param initialDelay delay before the second attempt
 * @param multiplier delay multiplier applied after each failure
 * @param maximumDelay upper bound for an individual delay
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maximumDelay) {
    /** Default policy: three attempts with one-second exponential backoff capped at 30 seconds. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30));

    /** Validates retry bounds and delay ordering. */
    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (initialDelay.isNegative() || initialDelay.isZero()) throw new IllegalArgumentException("initialDelay must be positive");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1");
        if (maximumDelay.compareTo(initialDelay) < 0) throw new IllegalArgumentException("maximumDelay must be >= initialDelay");
    }

    /**
     * Computes the delay before a one-based attempt number.
     *
     * @param attempt one-based attempt number
     * @return zero for the first attempt, otherwise a bounded backoff delay
     */
    public Duration delayBefore(int attempt) {
        if (attempt <= 1) return Duration.ZERO;
        double millis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 2);
        return Duration.ofMillis(Math.min(maximumDelay.toMillis(), Math.max(1L, (long) millis)));
    }
}
