package com.iantapply.orchestra.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {
    @Test
    void rendersSortedCountersAndLiveGauges() {
        MetricsRegistry metrics = new MetricsRegistry();
        AtomicInteger gauge = new AtomicInteger(3);
        metrics.increment("z_total");
        metrics.increment("z_total");
        metrics.gauge("a_current", gauge::get);

        assertEquals("z_total 2\na_current 3.0\n", metrics.prometheus());
        gauge.set(7);
        assertEquals("z_total 2\na_current 7.0\n", metrics.prometheus());
    }

    @Test
    void rejectsInvalidPrometheusNames() {
        MetricsRegistry metrics = new MetricsRegistry();

        assertThrows(IllegalArgumentException.class, () -> metrics.increment("bad-name"));
        assertThrows(IllegalArgumentException.class, () -> metrics.gauge("1bad", () -> 1));
    }
}
