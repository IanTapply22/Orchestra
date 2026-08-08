package com.iantapply.orchestra.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.api.ActionSpec;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.RetryPolicy;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class OrchestraAdministrationServiceTest {
    @Test
    void reloadIsAtomicAndAdministrationDelegatesToPublicService() {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry actions = new ActionRegistry();
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), actions, Clock.systemUTC(), 1, 16)) {
            DefaultOrchestraService orchestra = new DefaultOrchestraService(engine, actions, stores, stores);
            orchestra.registerAction("complete", ignored -> CompletableFuture.completedFuture(null));
            EventDefinition original = definition("original");
            EventDefinition replacement = definition("replacement");
            orchestra.registerDefinition(original);
            var administration = new OrchestraAdministrationService(
                    orchestra, stores, () -> new DefinitionValidationReport(List.of(replacement), List.of()));

            assertTrue(administration.reload().valid());
            assertEquals(List.of(replacement), administration.events());
            var executionId = administration.start(replacement.id());
            assertEquals(1, administration.executions(10).size());
            assertTrue(administration.cancel(executionId));
        }
    }

    @Test
    void invalidReloadPreservesExistingDefinitions() {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry actions = new ActionRegistry();
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), actions, Clock.systemUTC(), 1, 16)) {
            DefaultOrchestraService orchestra = new DefaultOrchestraService(engine, actions, stores, stores);
            EventDefinition original = definition("original");
            orchestra.registerDefinition(original);
            var administration = new OrchestraAdministrationService(
                    orchestra, stores, () -> new DefinitionValidationReport(List.of(), List.of("invalid")));

            assertFalse(administration.reload().valid());
            assertEquals(List.of(original), administration.events());
        }
    }

    private static EventDefinition definition(String id) {
        ActionSpec action = new ActionSpec("complete", "complete", Map.of(), RetryPolicy.DEFAULT);
        StageDefinition stage =
                new StageDefinition("stage", Duration.ZERO, Duration.ofSeconds(1), List.of(), List.of(action));
        return new EventDefinition(id, id, TargetSelector.ALL_ONLINE, List.of(stage));
    }
}
