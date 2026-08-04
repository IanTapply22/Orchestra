package com.iantapply.orchestra.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.api.*;
import java.time.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrchestratorEngineTest {
    @Test
    void advancesAllStagesAndDoesNotRepeatActions() throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerAction("count", context -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        EventDefinition definition = new EventDefinition(
                "test_event",
                "Test",
                TargetSelector.ALL_ONLINE,
                java.util.List.of(new StageDefinition(
                        "one",
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        java.util.List.of(),
                        java.util.List.of(new ActionSpec("increment", "count", Map.of(), RetryPolicy.DEFAULT)))));
        stores.save(definition);
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("one"), registry, Clock.systemUTC(), 1, 16)) {
            var id = engine.schedule(definition.id(), Instant.now(), Map.of());
            engine.start();
            await(() -> stores.find(id).orElseThrow().status() == EventStatus.COMPLETED);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void supportsControlOperationsVariablesAndListeners() {
        InMemoryStores stores = new InMemoryStores();
        stores.save(definitionWithNoActions());
        Clock clock = Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
        var transitions = new CopyOnWriteArrayList<String>();
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), new ActionRegistry(), clock, 1, 16)) {
            UUID id = engine.schedule("test_event", clock.instant().plusSeconds(30), Map.of("initial", true));
            engine.addListener((before, after) -> {
                throw new IllegalStateException("ignored listener");
            });
            engine.addListener((before, after) -> transitions.add(before.status() + "->" + after.status()));

            assertTrue(engine.pause(id));
            assertTrue(engine.setVariable(id, "multiplier", 2));
            assertTrue(engine.setVariable(id, "initial", null));
            assertTrue(engine.resume(id));
            assertTrue(engine.cancel(id));
            assertFalse(engine.pause(UUID.randomUUID()));

            var execution = stores.find(id).orElseThrow();
            assertEquals(EventStatus.CANCELLED, execution.status());
            assertEquals(2, execution.variables().get("multiplier"));
            assertFalse(execution.variables().containsKey("initial"));
            assertEquals(
                    java.util.List.of("SCHEDULED->PAUSED", "PAUSED->SCHEDULED", "SCHEDULED->CANCELLED"), transitions);
        }
    }

    @Test
    void failsExecutionWhenAConditionDoesNotPass() throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry registry = new ActionRegistry();
        registry.registerCondition("never", context -> CompletableFuture.completedFuture(false));
        StageDefinition stage = new StageDefinition(
                "guarded",
                Duration.ZERO,
                Duration.ofSeconds(1),
                java.util.List.of(new ConditionSpec("never", Map.of())),
                java.util.List.of());
        EventDefinition definition =
                new EventDefinition("guarded_event", "Guarded", TargetSelector.ALL_ONLINE, java.util.List.of(stage));
        stores.save(definition);
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), registry, Clock.systemUTC(), 1, 16)) {
            UUID id = engine.schedule(definition.id(), Instant.now(), Map.of());
            engine.start();

            await(() -> stores.find(id).orElseThrow().status() == EventStatus.FAILED);
            assertTrue(stores.find(id).orElseThrow().failure().contains("Conditions not met"));
        }
    }

    @Test
    void recoversStartingExecutionsAndRunsTheirAction() throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerAction("count", context -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        ActionSpec action = new ActionSpec("count", "count", Map.of(), null);
        StageDefinition stage = new StageDefinition(
                "one", Duration.ZERO, Duration.ofSeconds(1), java.util.List.of(), java.util.List.of(action));
        EventDefinition definition =
                new EventDefinition("recover_event", "Recover", TargetSelector.ALL_ONLINE, java.util.List.of(stage));
        stores.save(definition);
        Instant now = Instant.now();
        var starting = new com.iantapply.orchestra.domain.EventExecution(
                UUID.randomUUID(),
                definition.id(),
                EventStatus.STARTING,
                0,
                0,
                now,
                now,
                now,
                now,
                Map.of(),
                Set.of(),
                null);
        stores.create(starting);

        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), registry, Clock.systemUTC(), 1, 16)) {
            engine.recover();
            engine.start();
            await(() -> stores.find(starting.id()).orElseThrow().status() == EventStatus.COMPLETED);
            assertEquals(1, calls.get());
        }
    }

    private static EventDefinition definitionWithNoActions() {
        StageDefinition stage = new StageDefinition(
                "one", Duration.ZERO, Duration.ofSeconds(1), java.util.List.of(), java.util.List.of());
        return new EventDefinition("test_event", "Test", TargetSelector.ALL_ONLINE, java.util.List.of(stage));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long limit = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
