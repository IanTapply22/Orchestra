package com.iantapply.orchestra.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

/** Thread-safe registry that renders counters and gauges in Prometheus text format. */
public final class MetricsRegistry {
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();

    /** Creates an empty metrics registry. */
    public MetricsRegistry() {
    }

    /**
     * Increments a counter by one.
     *
     * @param name Prometheus-compatible counter name to increment
     */
    public void increment(String name) {
        validate(name);
        counters.computeIfAbsent(name, ignored -> new LongAdder()).increment();
    }

    /**
     * Registers or replaces a lazily evaluated gauge.
     *
     * @param name Prometheus-compatible gauge name
     * @param value value supplier called during rendering
     */
    public void gauge(String name, DoubleSupplier value) {
        validate(name);
        gauges.put(name, value);
    }

    /**
     * Renders all current metric values.
     *
     * @return current metrics in deterministic Prometheus text exposition order
     */
    public String prometheus() {
        StringBuilder output = new StringBuilder(512);
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendMetric(output, entry.getKey(), entry.getValue().sum()));
        gauges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendMetric(output, entry.getKey(), entry.getValue().getAsDouble()));
        return output.toString();
    }

    private static void appendMetric(StringBuilder output, String name, Number value) {
        output.append(name).append(' ').append(value).append('\n');
    }

    private static void validate(String name) {
        if (!name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) {
            throw new IllegalArgumentException("Invalid metric name: " + name);
        }
    }
}
