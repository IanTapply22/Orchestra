package com.iantapply.orchestra.schedule;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class CronExpressionTest {
    @Test void findsNamedWeekdayInConfiguredZone() {
        CronExpression cron = new CronExpression("0 18 * * FRI");
        ZoneId zone = ZoneId.of("America/Toronto");
        Instant after = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant();
        assertEquals(ZonedDateTime.of(2026, 8, 7, 18, 0, 0, 0, zone).toInstant(), cron.nextAfter(after, zone));
    }

    @Test void supportsRangesAndSteps() {
        CronExpression cron = new CronExpression("*/15 9-17 * * MON-FRI");
        assertTrue(cron.matches(Instant.parse("2026-08-03T13:30:00Z"), ZoneId.of("UTC")));
        assertFalse(cron.matches(Instant.parse("2026-08-03T13:31:00Z"), ZoneId.of("UTC")));
    }
}
