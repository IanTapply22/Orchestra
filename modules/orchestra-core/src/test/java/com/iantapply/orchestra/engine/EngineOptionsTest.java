package com.iantapply.orchestra.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EngineOptionsTest {
    @Test
    void acceptsExplicitValuesAndBuildsDefaults() {
        EngineOptions options =
                new EngineOptions(2, 32, Duration.ofMillis(50), 10, Duration.ofSeconds(5), Duration.ofSeconds(2));
        assertEquals(2, options.workerCount());
        assertEquals(256, EngineOptions.defaults(3, 64).pollBatchSize());
    }

    @Test
    void rejectsInvalidCapacitiesAndDurations() {
        assertThrows(IllegalArgumentException.class, () -> options(0, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> options(1, 0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> options(1, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> options(1, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> options(1, 1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> options(1, 1, 1, Duration.ofSeconds(-1)));
    }

    private static EngineOptions options(int workers, int queue, int batch, Duration poll) {
        return new EngineOptions(workers, queue, poll, batch, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
