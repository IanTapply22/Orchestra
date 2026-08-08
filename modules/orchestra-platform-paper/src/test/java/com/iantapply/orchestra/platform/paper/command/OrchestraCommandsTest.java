package com.iantapply.orchestra.platform.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.administration.DefaultOrchestraService;
import com.iantapply.orchestra.administration.DefinitionValidationReport;
import com.iantapply.orchestra.administration.OrchestraAdministrationService;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OrchestraCommandsTest {
    @Test
    void exposesOnlyTheDeliberatelySmallAdministrationSurface() {
        InMemoryStores stores = new InMemoryStores();
        ActionRegistry actions = new ActionRegistry();
        try (OrchestratorEngine engine = new OrchestratorEngine(
                stores, stores, stores, ignored -> Set.of("server"), actions, Clock.systemUTC(), 1, 16)) {
            var service = new DefaultOrchestraService(engine, actions, stores, stores);
            var administration = new OrchestraAdministrationService(
                    service, stores, () -> new DefinitionValidationReport(List.of(), List.of()));

            var command = new OrchestraCommands(administration, List::of).create();

            assertEquals(
                    Set.of("status", "events", "validate", "reload", "start", "cancel", "executions", "diagnostics"),
                    command.getChildren().stream().map(node -> node.getName()).collect(Collectors.toSet()));
            assertNotNull(command.getChild("start").getChild("event"));
            assertNotNull(command.getChild("cancel").getChild("execution"));
        }
    }
}
