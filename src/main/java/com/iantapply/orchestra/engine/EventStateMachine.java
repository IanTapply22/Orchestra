package com.iantapply.orchestra.engine;

import com.iantapply.orchestra.api.EventStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Validates legal transitions between persisted execution states. */
public final class EventStateMachine {
    private static final Map<EventStatus, Set<EventStatus>> ALLOWED = new EnumMap<>(EventStatus.class);

    static {
        ALLOWED.put(EventStatus.DRAFT, EnumSet.of(EventStatus.SCHEDULED, EventStatus.CANCELLED));
        ALLOWED.put(EventStatus.SCHEDULED, EnumSet.of(EventStatus.STARTING, EventStatus.PAUSED, EventStatus.CANCELLED));
        ALLOWED.put(EventStatus.STARTING, EnumSet.of(EventStatus.RUNNING, EventStatus.FAILED, EventStatus.CANCELLED));
        ALLOWED.put(
                EventStatus.RUNNING,
                EnumSet.of(
                        EventStatus.RUNNING,
                        EventStatus.PAUSED,
                        EventStatus.COMPLETED,
                        EventStatus.FAILED,
                        EventStatus.CANCELLED));
        ALLOWED.put(EventStatus.PAUSED, EnumSet.of(EventStatus.SCHEDULED, EventStatus.RUNNING, EventStatus.CANCELLED));
        ALLOWED.put(EventStatus.FAILED, EnumSet.of(EventStatus.SCHEDULED, EventStatus.CANCELLED));
        ALLOWED.put(EventStatus.COMPLETED, EnumSet.noneOf(EventStatus.class));
        ALLOWED.put(EventStatus.CANCELLED, EnumSet.noneOf(EventStatus.class));
    }

    /** Creates a validator for the fixed Orchestra transition graph. */
    public EventStateMachine() {}

    /**
     * Rejects a transition that is not present in the transition graph.
     *
     * @param from current status
     * @param to requested status
     * @throws IllegalStateException when the transition is not allowed
     */
    public void requireTransition(EventStatus from, EventStatus to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Illegal event transition: " + from + " -> " + to);
        }
    }
}
