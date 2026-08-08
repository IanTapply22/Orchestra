package com.iantapply.orchestra.api;

import java.time.ZoneId;

/**
 * Five-field cron schedule evaluated in a named time zone.
 *
 * @param cron five-field cron expression
 * @param zone evaluation zone, defaulting to UTC
 */
public record RecurringSchedule(String cron, ZoneId zone) {
    /** Validates the expression and applies the UTC default zone. */
    public RecurringSchedule {
        if (cron == null || cron.isBlank()) throw new IllegalArgumentException("cron is required");
        zone = zone == null ? ZoneId.of("UTC") : zone;
    }
}
