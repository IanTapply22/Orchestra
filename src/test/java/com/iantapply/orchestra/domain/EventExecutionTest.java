package com.iantapply.orchestra.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.api.EventStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventExecutionTest {
    @Test
    void scheduledFactoryCreatesInitialState() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-04T10:00:00Z");
        Instant due = now.plusSeconds(30);

        EventExecution execution = EventExecution.scheduled(id, "event", now, due);

        assertEquals(id, execution.id());
        assertEquals(EventStatus.SCHEDULED, execution.status());
        assertEquals(-1, execution.stageIndex());
        assertEquals(0, execution.version());
        assertEquals(due, execution.dueAt());
        assertNull(execution.stageStartedAt());
    }

    @Test
    void mutationsReturnIncrementedImmutableCopies() {
        Instant created = Instant.EPOCH;
        EventExecution original = EventExecution.scheduled(UUID.randomUUID(), "event", created, created);
        Map<String, Object> variables = new HashMap<>(Map.of("value", 1));

        EventExecution withVariables = original.withVariables(variables, created.plusSeconds(1));
        variables.put("value", 2);
        EventExecution running =
                withVariables.transition(EventStatus.RUNNING, 0, created.plusSeconds(2), created.plusSeconds(5));
        EventExecution completedAction = running.withCompletedAction("key", created.plusSeconds(3));
        EventExecution failed = completedAction.withFailure("boom", created.plusSeconds(4));

        assertEquals(1, withVariables.variables().get("value"));
        assertEquals(1, withVariables.version());
        assertEquals(created.plusSeconds(2), running.stageStartedAt());
        assertTrue(completedAction.completedActions().contains("key"));
        assertFalse(running.completedActions().contains("key"));
        assertEquals(EventStatus.FAILED, failed.status());
        assertEquals("boom", failed.failure());
        assertEquals(4, failed.version());
    }

    @Test
    void transitionWithinSameStagePreservesStageStart() {
        Instant now = Instant.EPOCH;
        EventExecution running = new EventExecution(
                UUID.randomUUID(), "event", EventStatus.RUNNING, 2, 4, now, now, now, now, Map.of(), Set.of(), null);

        EventExecution updated = running.transition(EventStatus.RUNNING, 2, now.plusSeconds(5), now.plusSeconds(10));

        assertEquals(now, updated.stageStartedAt());
    }
}
