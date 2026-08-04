package com.iantapply.orchestra.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.api.ActionSpec;
import com.iantapply.orchestra.api.ConditionSpec;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.EventStatus;
import com.iantapply.orchestra.api.RetryPolicy;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import com.iantapply.orchestra.domain.EventExecution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StageExecutorTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void evaluatesEveryConditionUntilOneFails() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerCondition("yes", context -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });
        registry.registerCondition("no", context -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(false);
        });
        StageDefinition stage = new StageDefinition(
                "stage",
                Duration.ZERO,
                Duration.ofSeconds(1),
                List.of(new ConditionSpec("yes", Map.of()), new ConditionSpec("no", Map.of())),
                List.of());
        EventDefinition event = event(stage);
        StageExecutor executor = executor(registry, (before, after) -> true);

        assertFalse(executor.conditionsPass(execution(), event, stage));
        assertEquals(2, calls.get());
    }

    @Test
    void retriesFailedActionsAndPersistsCompletionOnce() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger attempts = new AtomicInteger();
        registry.registerAction("flaky", context -> {
            if (attempts.incrementAndGet() < 3) {
                return CompletableFuture.failedFuture(new IllegalStateException("retry"));
            }
            return CompletableFuture.completedFuture(null);
        });
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), 1, Duration.ofMillis(1));
        ActionSpec action = new ActionSpec("call", "flaky", Map.of(), retry);
        StageDefinition stage = stage(action);
        EventDefinition event = event(stage);
        AtomicInteger writes = new AtomicInteger();
        StageExecutor executor = executor(registry, (before, after) -> {
            writes.incrementAndGet();
            return true;
        });

        EventExecution result = executor.executeActions(execution(), event, stage);

        assertEquals(3, attempts.get());
        assertEquals(1, writes.get());
        assertTrue(result.completedActions().contains(result.id() + ":stage:call:server-1"));
    }

    @Test
    void setVariableIsEngineLocalAndCanRemoveValues() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        ActionSpec set = new ActionSpec("set", "set_variable", Map.of("key", "multiplier", "value", 2), null);
        StageDefinition setStage = stage(set);
        StageExecutor executor = executor(registry, (before, after) -> true);

        EventExecution updated = executor.executeActions(execution(), event(setStage), setStage);

        assertEquals(2, updated.variables().get("multiplier"));
        ActionSpec remove = new ActionSpec("remove", "set_variable", Map.of("key", "multiplier"), null);
        StageDefinition removeStage = stage(remove);
        EventExecution removed = executor.executeActions(updated, event(removeStage), removeStage);
        assertFalse(removed.variables().containsKey("multiplier"));
    }

    @Test
    void skipsCompletedActionsAndRejectsConcurrentPersistenceChanges() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerAction("count", context -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        ActionSpec action = new ActionSpec("call", "count", Map.of(), null);
        StageDefinition stage = stage(action);
        EventExecution initial = execution();
        String key = initial.id() + ":stage:call:server-1";
        EventExecution alreadyDone = new EventExecution(
                initial.id(),
                initial.definitionId(),
                initial.status(),
                initial.stageIndex(),
                initial.version(),
                initial.createdAt(),
                initial.updatedAt(),
                initial.dueAt(),
                initial.stageStartedAt(),
                initial.variables(),
                Set.of(key),
                null);

        EventExecution unchanged =
                executor(registry, (before, after) -> true).executeActions(alreadyDone, event(stage), stage);
        assertEquals(alreadyDone, unchanged);
        assertEquals(0, calls.get());

        StageExecutor conflicting = executor(registry, (before, after) -> false);
        assertThrows(IllegalStateException.class, () -> conflicting.executeActions(initial, event(stage), stage));
    }

    private static StageExecutor executor(
            ActionRegistry registry, java.util.function.BiPredicate<EventExecution, EventExecution> persist) {
        return new StageExecutor(registry, ignored -> Set.of("server-1"), CLOCK, persist);
    }

    private static EventExecution execution() {
        return new EventExecution(
                UUID.randomUUID(),
                "test_event",
                EventStatus.STARTING,
                0,
                0,
                NOW,
                NOW,
                NOW,
                NOW,
                Map.of(),
                Set.of(),
                null);
    }

    private static EventDefinition event(StageDefinition stage) {
        return new EventDefinition("test_event", "Test", TargetSelector.ALL_ONLINE, List.of(stage));
    }

    private static StageDefinition stage(ActionSpec action) {
        return new StageDefinition("stage", Duration.ZERO, Duration.ofSeconds(1), List.of(), List.of(action));
    }
}
