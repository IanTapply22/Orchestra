package com.iantapply.orchestra.port;

import com.iantapply.orchestra.api.EventDefinition;
import java.util.Collection;
import java.util.Optional;

/** Persistence contract for event definitions. */
public interface DefinitionRepository {
    /**
     * Inserts or replaces a definition.
     *
     * @param definition definition to insert or replace by identifier
     */
    void save(EventDefinition definition);

    /**
     * Finds one definition.
     *
     * @param id event identifier
     * @return matching definition, if present
     */
    Optional<EventDefinition> find(String id);

    /**
     * Lists known definitions.
     *
     * @return snapshot of all known definitions
     */
    Collection<EventDefinition> findAll();
}
