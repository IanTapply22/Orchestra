package com.iantapply.orchestra.administration;

import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.OrchestraService;
import com.iantapply.orchestra.api.OrchestraStatus;
import com.iantapply.orchestra.domain.EventExecution;
import com.iantapply.orchestra.port.DefinitionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Platform-neutral operations used by administrative command and HTTP adapters. */
public final class OrchestraAdministrationService {
    private final OrchestraService orchestra;
    private final DefinitionRepository definitions;
    private final Supplier<DefinitionValidationReport> validator;

    /**
     * Creates the administration facade.
     *
     * @param orchestra public orchestration service
     * @param definitions mutable definition repository
     * @param validator non-mutating definition validator
     */
    public OrchestraAdministrationService(
            OrchestraService orchestra,
            DefinitionRepository definitions,
            Supplier<DefinitionValidationReport> validator) {
        this.orchestra = Objects.requireNonNull(orchestra);
        this.definitions = Objects.requireNonNull(definitions);
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * Returns current operational status.
     *
     * @return current status snapshot
     */
    public OrchestraStatus status() {
        return orchestra.status();
    }

    /**
     * Returns registered event definitions in deterministic identifier order.
     *
     * @return sorted immutable definition list
     */
    public List<EventDefinition> events() {
        return orchestra.definitions().stream()
                .sorted(java.util.Comparator.comparing(EventDefinition::id))
                .toList();
    }

    /**
     * Validates definitions without modifying the running service.
     *
     * @return complete validation report
     */
    public DefinitionValidationReport validate() {
        return validator.get();
    }

    /**
     * Atomically replaces definitions only when the complete validation succeeds.
     *
     * @return complete validation report
     */
    public DefinitionValidationReport reload() {
        DefinitionValidationReport report = validate();
        if (report.valid()) definitions.replaceAll(report.definitions());
        return report;
    }

    /**
     * Starts one registered event immediately.
     *
     * @param eventId registered event identifier
     * @return new execution identifier
     */
    public UUID start(String eventId) {
        return orchestra.startNow(eventId);
    }

    /**
     * Cancels one execution when its current state permits it.
     *
     * @param executionId execution identifier
     * @return whether cancellation succeeded
     */
    public boolean cancel(UUID executionId) {
        return orchestra.cancel(executionId);
    }

    /**
     * Returns active execution snapshots up to the requested limit.
     *
     * @param limit maximum results
     * @return active execution snapshots
     */
    public Collection<EventExecution> executions(int limit) {
        return orchestra.activeExecutions(limit);
    }
}
