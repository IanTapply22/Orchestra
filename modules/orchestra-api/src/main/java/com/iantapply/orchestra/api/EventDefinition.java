package com.iantapply.orchestra.api;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable definition of a complete event workflow.
 *
 * @param id stable lowercase event identifier
 * @param displayName human-readable event name
 * @param schedule recurring schedule, or {@code null} for manually started events
 * @param targets servers eligible to execute the event
 * @param stages ordered workflow stages
 */
public record EventDefinition(
        String id,
        String displayName,
        RecurringSchedule schedule,
        TargetSelector targets,
        List<StageDefinition> stages) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");

    /** Validates the definition and snapshots its ordered stages. */
    public EventDefinition {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid event id: " + id);
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("Display name is required");
        if (targets == null) throw new IllegalArgumentException("Targets are required");
        stages = List.copyOf(stages);
        if (stages.isEmpty()) throw new IllegalArgumentException("At least one stage is required");
        Set<String> ids = new HashSet<>();
        for (StageDefinition stage : stages) {
            if (!ids.add(stage.id())) throw new IllegalArgumentException("Duplicate stage id: " + stage.id());
        }
    }

    /**
     * Creates a definition without a recurring schedule.
     *
     * @param id stable event identifier
     * @param displayName human-readable event name
     * @param targets servers eligible to execute the event
     * @param stages ordered workflow stages
     */
    public EventDefinition(String id, String displayName, TargetSelector targets, List<StageDefinition> stages) {
        this(id, displayName, null, targets, stages);
    }
}
