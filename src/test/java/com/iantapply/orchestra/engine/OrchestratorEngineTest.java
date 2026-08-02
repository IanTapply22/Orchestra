package com.iantapply.orchestra.engine;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.api.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorEngineTest {
    @Test void advancesAllStagesAndDoesNotRepeatActions() throws Exception {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerAction("count", context -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        EventDefinition definition = new EventDefinition("test_event", "Test", TargetSelector.ALL_ONLINE,
                java.util.List.of(new StageDefinition("one", Duration.ZERO, Duration.ofSeconds(1), java.util.List.of(),
                        java.util.List.of(new ActionSpec("increment", "count", Map.of(), RetryPolicy.DEFAULT)))));
        stores.save(definition);
        try (OrchestratorEngine engine = new OrchestratorEngine(stores, stores, stores, ignored -> Set.of("one"),
                registry, Clock.systemUTC(), 1, 16)) {
            var id = engine.schedule(definition.id(), Instant.now(), Map.of());
            engine.start();
            await(() -> stores.find(id).orElseThrow().status() == EventStatus.COMPLETED);
            assertEquals(1, calls.get());
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long limit = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
