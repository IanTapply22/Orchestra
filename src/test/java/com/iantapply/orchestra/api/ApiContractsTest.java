package com.iantapply.orchestra.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiContractsTest {
    @Test
    void actionContextSnapshotsVariablesAndBuildsStableKeys() {
        Map<String, Object> variables = new HashMap<>(Map.of("round", 2));
        ActionSpec action = new ActionSpec("announce", "broadcast", Map.of("message", "Hello"), null);
        StageDefinition stage = stage("opening", List.of(action));
        EventDefinition event = event(List.of(stage));
        UUID executionId = UUID.randomUUID();

        ActionContext context =
                new ActionContext(executionId, event, stage, action, "survival-1", Instant.EPOCH, variables);
        variables.put("round", 3);

        assertEquals(2, context.variables().get("round"));
        assertEquals("Hello", context.getString("message"));
        assertEquals(executionId + ":opening:announce:survival-1", context.idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> context.getString("missing"));
    }

    @Test
    void definitionNormalizesIdsAndRejectsInvalidShapes() {
        StageDefinition stage = stage("one", List.of());
        EventDefinition definition =
                new EventDefinition("WEEKEND_XP", "Weekend XP", TargetSelector.ALL_ONLINE, List.of(stage));

        assertEquals("weekend_xp", definition.id());
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventDefinition("x", "Short", TargetSelector.ALL_ONLINE, List.of(stage)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventDefinition("valid_id", "Name", TargetSelector.ALL_ONLINE, List.of(stage, stage)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventDefinition("valid_id", "Name", TargetSelector.ALL_ONLINE, List.of()));
    }

    @Test
    void specsAndSelectorsSnapshotCallerCollections() {
        Map<String, Object> arguments = new HashMap<>(Map.of("message", "before"));
        ActionSpec action = new ActionSpec("a", "broadcast", arguments, null);
        arguments.put("message", "after");

        Set<String> servers = new java.util.HashSet<>(Set.of("one"));
        TargetSelector selector = new TargetSelector(servers, Set.of(), Map.of(), false);
        servers.add("two");

        assertEquals("before", action.arguments().get("message"));
        assertEquals(Set.of("one"), selector.servers());
        assertEquals(RetryPolicy.DEFAULT, action.retryPolicy());
        assertThrows(IllegalArgumentException.class, () -> new TargetSelector(Set.of(), Set.of(), Map.of(), false));
    }

    @Test
    void stagesValidateTimingAndSnapshotLists() {
        List<ActionSpec> actions = new ArrayList<>();
        StageDefinition stage = new StageDefinition("one", null, null, List.of(), actions);
        actions.add(new ActionSpec("late", "command", Map.of(), null));

        assertEquals(Duration.ZERO, stage.duration());
        assertEquals(Duration.ofMinutes(5), stage.timeout());
        assertEquals(List.of(), stage.actions());
        assertThrows(
                IllegalArgumentException.class,
                () -> new StageDefinition("one", Duration.ofSeconds(-1), Duration.ofSeconds(1), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StageDefinition("one", Duration.ZERO, Duration.ZERO, List.of(), List.of()));
    }

    @Test
    void retryPolicyCalculatesBoundedExponentialBackoff() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(10), 3, Duration.ofMillis(50));

        assertEquals(Duration.ZERO, policy.delayBefore(1));
        assertEquals(Duration.ofMillis(10), policy.delayBefore(2));
        assertEquals(Duration.ofMillis(30), policy.delayBefore(3));
        assertEquals(Duration.ofMillis(50), policy.delayBefore(4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryPolicy(0, Duration.ofSeconds(1), 1, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryPolicy(1, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1)));
    }

    @Test
    void recurringSchedulesDefaultToUtc() {
        RecurringSchedule schedule = new RecurringSchedule("0 18 * * FRI", null);

        assertEquals(ZoneId.of("UTC"), schedule.zone());
        assertThrows(IllegalArgumentException.class, () -> new RecurringSchedule(" ", null));
    }

    private static EventDefinition event(List<StageDefinition> stages) {
        return new EventDefinition("test_event", "Test Event", TargetSelector.ALL_ONLINE, stages);
    }

    private static StageDefinition stage(String id, List<ActionSpec> actions) {
        return new StageDefinition(id, Duration.ZERO, Duration.ofSeconds(1), List.of(), actions);
    }
}
