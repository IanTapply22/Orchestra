package com.iantapply.orchestra.engine;

import com.iantapply.orchestra.api.OrchestraAction;
import com.iantapply.orchestra.api.OrchestraCondition;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe registry of named action and condition extensions. */
public final class ActionRegistry {
    private final ConcurrentMap<String, OrchestraAction> actions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OrchestraCondition> conditions = new ConcurrentHashMap<>();

    /** Creates an empty registry. */
    public ActionRegistry() {
    }

    /**
     * Registers a unique action type.
     *
     * @param type case-insensitive type name
     * @param action implementation
     * @throws IllegalStateException if the type is already registered
     */
    public void registerAction(String type, OrchestraAction action) {
        if (actions.putIfAbsent(normalize(type), Objects.requireNonNull(action)) != null) {
            throw new IllegalStateException("Action already registered: " + type);
        }
    }

    /**
     * Registers a unique condition type.
     *
     * @param type case-insensitive type name
     * @param condition implementation
     * @throws IllegalStateException if the type is already registered
     */
    public void registerCondition(String type, OrchestraCondition condition) {
        if (conditions.putIfAbsent(normalize(type), Objects.requireNonNull(condition)) != null) {
            throw new IllegalStateException("Condition already registered: " + type);
        }
    }

    /**
     * Looks up a registered action.
     *
     * @param type case-insensitive action type
     * @return registered action
     * @throws IllegalArgumentException if the type is unknown
     */
    public OrchestraAction action(String type) {
        OrchestraAction action = actions.get(normalize(type));
        if (action == null) throw new IllegalArgumentException("Unknown action type: " + type);
        return action;
    }

    /**
     * Looks up a registered condition.
     *
     * @param type case-insensitive condition type
     * @return registered condition
     * @throws IllegalArgumentException if the type is unknown
     */
    public OrchestraCondition condition(String type) {
        OrchestraCondition condition = conditions.get(normalize(type));
        if (condition == null) throw new IllegalArgumentException("Unknown condition type: " + type);
        return condition;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
