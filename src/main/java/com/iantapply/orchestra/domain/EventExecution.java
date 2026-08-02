package com.iantapply.orchestra.domain;

import com.iantapply.orchestra.api.EventStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, versioned runtime state for one event execution.
 *
 * @param id unique execution identifier
 * @param definitionId source definition identifier
 * @param status current lifecycle status
 * @param stageIndex zero-based current stage, or {@code -1} before starting
 * @param version optimistic-lock version
 * @param createdAt creation time
 * @param updatedAt last persisted update time
 * @param dueAt next time at which the engine should process this execution
 * @param stageStartedAt time at which the current stage began
 * @param variables immutable global variables for this execution
 * @param completedActions idempotency keys already persisted as complete
 * @param failure terminal failure detail, when failed
 */
public record EventExecution(
        UUID id,
        String definitionId,
        EventStatus status,
        int stageIndex,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Instant stageStartedAt,
        Map<String, Object> variables,
        Set<String> completedActions,
        String failure
) {
    /** Snapshots mutable collections before execution state is shared. */
    public EventExecution {
        variables = Map.copyOf(variables);
        completedActions = Set.copyOf(completedActions);
    }

    /**
     * Creates a new scheduled execution at version zero.
     *
     * @param id unique execution identifier
     * @param definitionId source definition identifier
     * @param now creation time
     * @param dueAt requested start time
     * @return new scheduled execution
     */
    public static EventExecution scheduled(UUID id, String definitionId, Instant now, Instant dueAt) {
        return new EventExecution(id, definitionId, EventStatus.SCHEDULED, -1, 0, now, now, dueAt, null,
                Map.of(), Set.of(), null);
    }

    /**
     * Creates the next version with a new lifecycle state.
     *
     * @param next next status
     * @param nextStage next stage index
     * @param now transition time
     * @param nextDueAt next processing time
     * @return incremented immutable execution
     */
    public EventExecution transition(EventStatus next, int nextStage, Instant now, Instant nextDueAt) {
        return new EventExecution(id, definitionId, next, nextStage, version + 1, createdAt, now, nextDueAt,
                nextStage == stageIndex ? stageStartedAt : now, variables, completedActions, failure);
    }

    /**
     * Creates a failed copy of this execution.
     *
     * @param message failure description
     * @param now failure time
     * @return incremented failed execution
     */
    public EventExecution withFailure(String message, Instant now) {
        return new EventExecution(id, definitionId, EventStatus.FAILED, stageIndex, version + 1, createdAt, now,
                null, stageStartedAt, variables, completedActions, message);
    }

    /**
     * Records one action as durably completed.
     *
     * @param key completed action idempotency key
     * @param now completion time
     * @return incremented execution containing the key
     */
    public EventExecution withCompletedAction(String key, Instant now) {
        var updated = new java.util.HashSet<>(completedActions);
        updated.add(key);
        return new EventExecution(id, definitionId, status, stageIndex, version + 1, createdAt, now, dueAt,
                stageStartedAt, variables, updated, failure);
    }

    /**
     * Replaces the complete variable snapshot.
     *
     * @param nextVariables complete replacement variable map
     * @param now update time
     * @return incremented execution with an immutable variable snapshot
     */
    public EventExecution withVariables(Map<String, Object> nextVariables, Instant now) {
        return new EventExecution(id, definitionId, status, stageIndex, version + 1, createdAt, now, dueAt,
                stageStartedAt, nextVariables, completedActions, failure);
    }
}
