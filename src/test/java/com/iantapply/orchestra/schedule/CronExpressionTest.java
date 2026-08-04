package com.iantapply.orchestra.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class CronExpressionTest {
    @Test
    void findsNamedWeekdayInConfiguredZone() {
        CronExpression cron = new CronExpression("0 18 * * FRI");
        ZoneId zone = ZoneId.of("America/Toronto");
        Instant after = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant();
        assertEquals(ZonedDateTime.of(2026, 8, 7, 18, 0, 0, 0, zone).toInstant(), cron.nextAfter(after, zone));
    }

    @Test
    void supportsRangesAndSteps() {
        CronExpression cron = new CronExpression("*/15 9-17 * * MON-FRI");
        assertTrue(cron.matches(Instant.parse("2026-08-03T13:30:00Z"), ZoneId.of("UTC")));
        assertFalse(cron.matches(Instant.parse("2026-08-03T13:31:00Z"), ZoneId.of("UTC")));
    }

    @Test
    void rejectsMalformedAndOutOfRangeExpressions() {
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("* * * *"));
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("60 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("*/0 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("10-5 * * * *"));
    }

    @Test
    void supportsNamedMonthsAndSundaySeven() {
        CronExpression cron = new CronExpression("0 0 2 AUG 7");
        assertTrue(cron.matches(Instant.parse("2026-08-02T00:00:00Z"), ZoneId.of("UTC")));
    }
}
