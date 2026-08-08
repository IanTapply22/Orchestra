package com.iantapply.orchestra.administration;

import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.EventLifecycleListener;
import com.iantapply.orchestra.api.OrchestraAction;
import com.iantapply.orchestra.api.OrchestraCondition;
import com.iantapply.orchestra.api.OrchestraService;
import com.iantapply.orchestra.api.OrchestraStatus;
import com.iantapply.orchestra.domain.EventExecution;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.ExecutionRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Default platform-neutral implementation of the public administration facade. */
public final class DefaultOrchestraService implements OrchestraService {
    private final OrchestratorEngine engine;
    private final ActionRegistry actions;
    private final DefinitionRepository definitions;
    private final ExecutionRepository executions;

    /**
     * Creates a facade over one fully wired engine and its repositories.
     *
     * @param engine orchestration engine
     * @param actions action and condition registry
     * @param definitions definition repository
     * @param executions execution repository
     */
    public DefaultOrchestraService(
            OrchestratorEngine engine,
            ActionRegistry actions,
            DefinitionRepository definitions,
            ExecutionRepository executions) {
        this.engine = Objects.requireNonNull(engine);
        this.actions = Objects.requireNonNull(actions);
        this.definitions = Objects.requireNonNull(definitions);
        this.executions = Objects.requireNonNull(executions);
    }

    @Override
    public void registerDefinition(EventDefinition definition) {
        definitions.save(Objects.requireNonNull(definition));
    }

    @Override
    public void registerAction(String type, OrchestraAction action) {
        actions.registerAction(type, action);
    }

    @Override
    public void registerCondition(String type, OrchestraCondition condition) {
        actions.registerCondition(type, condition);
    }

    @Override
    public UUID startNow(String definitionId) {
        return engine.startNow(definitionId);
    }

    @Override
    public UUID schedule(String definitionId, Instant startAt, Map<String, Object> variables) {
        return engine.schedule(definitionId, startAt, variables);
    }

    @Override
    public void addListener(EventLifecycleListener listener) {
        engine.addListener(listener);
    }

    @Override
    public boolean pause(UUID executionId) {
        return engine.pause(executionId);
    }

    @Override
    public boolean resume(UUID executionId) {
        return engine.resume(executionId);
    }

    @Override
    public boolean cancel(UUID executionId) {
        return engine.cancel(executionId);
    }

    @Override
    public boolean retry(UUID executionId) {
        return engine.retry(executionId);
    }

    @Override
    public boolean setVariable(UUID executionId, String key, Object value) {
        return engine.setVariable(executionId, key, value);
    }

    @Override
    public Optional<EventExecution> execution(UUID executionId) {
        return executions.find(executionId);
    }

    @Override
    public Collection<EventDefinition> definitions() {
        return List.copyOf(definitions.findAll());
    }

    @Override
    public Collection<EventExecution> activeExecutions(int limit) {
        return List.copyOf(executions.findActive(limit));
    }

    @Override
    public OrchestraStatus status() {
        return new OrchestraStatus(
                definitions.findAll().size(),
                executions.findActive(10_000).size(),
                engine.activeWorkerCount(),
                engine.queuedTaskCount());
    }
}
