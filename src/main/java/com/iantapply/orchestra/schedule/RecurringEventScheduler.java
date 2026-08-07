package com.iantapply.orchestra.schedule;

import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.DistributedLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls recurring definitions and uses distributed leases to emit each minute-level
 * occurrence at most once across concurrently running nodes.
 */
public final class RecurringEventScheduler implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(RecurringEventScheduler.class.getName());
    private final DefinitionRepository definitions;
    private final OrchestratorEngine engine;
    private final DistributedLock locks;
    private final Clock clock;
    private final Map<String, Instant> localOccurrences = new ConcurrentHashMap<>();
    private final Map<String, ParsedCron> expressions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("orchestra-cron", 0).factory());
    private final AtomicBoolean open = new AtomicBoolean();

    /**
     * Creates a distributed recurring-event dispatcher.
     *
     * @param definitions definitions containing optional recurring schedules
     * @param engine engine receiving scheduled executions
     * @param locks lease provider used to deduplicate occurrences
     * @param clock scheduler clock
     */
    public RecurringEventScheduler(
            DefinitionRepository definitions, OrchestratorEngine engine, DistributedLock locks, Clock clock) {
        this.definitions = definitions;
        this.engine = engine;
        this.locks = locks;
        this.clock = clock;
    }

    /** Starts the recurring schedule poller once. */
    public void start() {
        if (open.compareAndSet(false, true)) {
            timer.scheduleWithFixedDelay(this::safeTick, 0, 30, TimeUnit.SECONDS);
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Recurring event polling failed", failure);
        }
    }

    void tick() {
        Instant now = clock.instant();
        Instant minute = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        for (EventDefinition event : definitions.findAll()) {
            if (event.schedule() == null) {
                continue;
            }
            CronExpression cron = expressions
                    .compute(
                            event.id(),
                            (ignored, cached) -> cached != null
                                            && cached.source()
                                                    .equals(event.schedule().cron())
                                    ? cached
                                    : new ParsedCron(
                                            event.schedule().cron(),
                                            new CronExpression(event.schedule().cron())))
                    .expression();
            if (!cron.matches(minute, event.schedule().zone())) {
                continue;
            }
            String occurrence = event.id() + ":" + minute;
            if (localOccurrences.putIfAbsent(occurrence, minute) != null) {
                continue;
            }
            try (var lease = locks.tryAcquire("cron:" + occurrence, Duration.ofMinutes(2))
                    .orElse(null)) {
                if (lease != null) {
                    engine.schedule(event.id(), minute, Map.of("scheduled_at", minute.toString()));
                }
            }
        }
        Instant retentionBoundary = minute.minus(Duration.ofMinutes(3));
        localOccurrences.entrySet().removeIf(entry -> entry.getValue().isBefore(retentionBoundary));
    }

    /** Stops future recurring schedule checks. */
    @Override
    public void close() {
        open.set(false);
        timer.shutdownNow();
    }

    private record ParsedCron(String source, CronExpression expression) {}
}
