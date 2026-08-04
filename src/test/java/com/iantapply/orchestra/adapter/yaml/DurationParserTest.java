package com.iantapply.orchestra.adapter.yaml;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationParserTest {
    @Test
    void parsesCompoundDurations() {
        assertEquals(Duration.ofMinutes(90).plusSeconds(5), DurationParser.parse("1h30m5s"));
        assertEquals(Duration.ofHours(48), DurationParser.parse("2d"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("1 hour"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("1h-2m"));
        assertThrows(ArithmeticException.class, () -> DurationParser.parse(Long.MAX_VALUE + "d"));
    }

    @Test
    void supportsDefaultsMillisecondsAndIsoDurations() {
        assertEquals(Duration.ZERO, DurationParser.parse(null));
        assertEquals(Duration.ofMillis(250), DurationParser.parse("250ms"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("PT5M"));
    }
}
