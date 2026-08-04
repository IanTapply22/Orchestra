package com.iantapply.orchestra.port;

import com.iantapply.orchestra.domain.EventExecution;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Durable execution store using optimistic version checks for updates. */
public interface ExecutionRepository {
    /**
     * Persists a new execution.
     *
     * @param execution new execution to persist
     */
    void create(EventExecution execution);

    /**
     * Finds one execution.
     *
     * @param id execution identifier
     * @return matching execution, if present
     */
    Optional<EventExecution> find(UUID id);

    /**
     * Finds runnable work due on or before a time.
     *
     * @param now inclusive due-time boundary
     * @param limit maximum results
     * @return runnable executions ordered by due time when supported
     */
    Collection<EventExecution> findDue(Instant now, int limit);

    /**
     * Finds execution state eligible for startup recovery.
     *
     * @param limit maximum results
     * @return executions eligible for startup recovery
     */
    Collection<EventExecution> findActive(int limit);

    /**
     * Replaces an execution only if its current version equals the expected version.
     *
     * @param expectedVersion version observed by the caller
     * @param replacement complete replacement value
     * @return whether the replacement was persisted
     */
    boolean compareAndSet(long expectedVersion, EventExecution replacement);
}
