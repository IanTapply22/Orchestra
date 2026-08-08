package com.iantapply.orchestra.api;

import com.iantapply.orchestra.domain.EventExecution;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public facade exposed by the installed Orchestra Paper plugin. */
public interface OrchestraService {
    /**
     * Registers or replaces an event definition by identifier.
     *
     * @param definition validated definition used by future processing
     */
    void registerDefinition(EventDefinition definition);

    /**
     * Registers a custom action type for this server session.
     *
     * @param type unique action type used in definitions
     * @param action asynchronous action implementation
     */
    void registerAction(String type, OrchestraAction action);

    /**
     * Registers a custom condition type for this server session.
     *
     * @param type unique condition type used in definitions
     * @param condition asynchronous condition implementation
     */
    void registerCondition(String type, OrchestraCondition condition);

    /**
     * Starts a known event immediately.
     *
     * @param definitionId registered event identifier
     * @return durable execution identifier
     */
    UUID startNow(String definitionId);

    /**
     * Creates a durable execution at the requested time with initial variables.
     *
     * @param definitionId registered event identifier
     * @param startAt requested start time
     * @param variables initial execution variables
     * @return durable execution identifier
     */
    UUID schedule(String definitionId, Instant startAt, Map<String, Object> variables);

    /**
     * Registers a listener for persisted lifecycle transitions.
     *
     * @param listener transition listener retained for this server session
     */
    void addListener(EventLifecycleListener listener);

    /**
     * Pauses an execution if its current state permits the transition.
     *
     * @param executionId execution identifier
     * @return whether the optimistic update succeeded
     */
    boolean pause(UUID executionId);

    /**
     * Resumes a paused execution.
     *
     * @param executionId execution identifier
     * @return whether the optimistic update succeeded
     */
    boolean resume(UUID executionId);

    /**
     * Cancels an execution if its current state permits the transition.
     *
     * @param executionId execution identifier
     * @return whether the optimistic update succeeded
     */
    boolean cancel(UUID executionId);

    /**
     * Restarts a failed execution from its first stage.
     *
     * @param executionId execution identifier
     * @return whether the optimistic update succeeded
     */
    boolean retry(UUID executionId);

    /**
     * Sets an execution variable, or removes it when {@code value} is {@code null}.
     *
     * @param executionId execution identifier
     * @param key variable key
     * @param value replacement value, or {@code null} to remove it
     * @return whether the optimistic update succeeded
     */
    boolean setVariable(UUID executionId, String key, Object value);

    /**
     * Returns one current execution snapshot.
     *
     * @param executionId execution identifier
     * @return current immutable snapshot, if it exists
     */
    Optional<EventExecution> execution(UUID executionId);

    /**
     * Returns a snapshot of all registered definitions.
     *
     * @return immutable definition snapshot
     */
    Collection<EventDefinition> definitions();

    /**
     * Returns active execution snapshots up to the requested limit.
     *
     * @param limit maximum results
     * @return immutable active execution snapshot
     */
    Collection<EventExecution> activeExecutions(int limit);

    /**
     * Returns current platform-neutral administration status.
     *
     * @return current status snapshot
     */
    OrchestraStatus status();
}
