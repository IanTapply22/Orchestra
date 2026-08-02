package com.iantapply.orchestra.adapter.yaml;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {
    @Test void parsesCompoundDurations() {
        assertEquals(Duration.ofMinutes(90).plusSeconds(5), DurationParser.parse("1h30m5s"));
        assertEquals(Duration.ofHours(48), DurationParser.parse("2d"));
    }

    @Test void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("1 hour"));
    }
}
