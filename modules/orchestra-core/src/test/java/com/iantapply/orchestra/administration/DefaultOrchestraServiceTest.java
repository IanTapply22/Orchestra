package com.iantapply.orchestra.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class DefaultOrchestraServiceTest {
    @Test
    void delegatesRegistrationExecutionAndStatus() {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry actions = new ActionRegistry();
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), actions, Clock.systemUTC(), 1, 16)) {
            var service = new DefaultOrchestraService(engine, actions, stores, stores);
            service.registerAction("complete", ignored -> CompletableFuture.completedFuture(null));
            EventDefinition definition = definition();
            service.registerDefinition(definition);

            var executionId = service.startNow(definition.id());

            assertEquals(definition, service.definitions().iterator().next());
            assertTrue(service.execution(executionId).isPresent());
            assertEquals(1, service.activeExecutions(10).size());
            assertEquals(1, service.status().definitionCount());
            assertTrue(service.status().summary().contains("definitions=1"));
        }
    }

    @Test
    void validationReportOwnsConsistentFormatting() {
        var valid = new DefinitionValidationReport(List.of(definition()), List.of());
        var invalid = new DefinitionValidationReport(List.of(), List.of("first", "second"));

        assertEquals("Validated 1 event definition(s)", valid.summary());
        assertEquals("2 validation error(s): first; second", invalid.summary());
    }

    private static EventDefinition definition() {
        ActionSpec action = new ActionSpec("complete", "complete", Map.of(), RetryPolicy.DEFAULT);
        StageDefinition stage =
                new StageDefinition("stage", Duration.ZERO, Duration.ofSeconds(1), List.of(), List.of(action));
        return new EventDefinition("service_event", "Service", TargetSelector.ALL_ONLINE, List.of(stage));
    }
}
