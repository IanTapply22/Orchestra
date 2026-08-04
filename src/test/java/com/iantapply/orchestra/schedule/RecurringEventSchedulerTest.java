package com.iantapply.orchestra.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.RecurringSchedule;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecurringEventSchedulerTest {
    @Test
    void schedulesMatchingOccurrenceOnlyOncePerScheduler() {
        Instant minute = Instant.parse("2026-08-04T12:00:00Z");
        Clock clock = Clock.fixed(minute.plusSeconds(20), ZoneOffset.UTC);
        InMemoryStores stores = new InMemoryStores();
        stores.save(definition("matching", "0 12 * * *"));
        stores.save(definition("not_matching", "1 12 * * *"));

        try (OrchestratorEngine engine = new OrchestratorEngine(
                        stores, stores, stores, ignored -> Set.of("server"), new ActionRegistry(), clock, 1, 16);
                RecurringEventScheduler scheduler = new RecurringEventScheduler(stores, engine, stores, clock)) {
            scheduler.tick();
            scheduler.tick();

            var due = stores.findDue(minute, 10);
            assertEquals(1, due.size());
            assertEquals("matching", due.iterator().next().definitionId());
            assertEquals(minute.toString(), due.iterator().next().variables().get("scheduled_at"));
        }
    }

    private static EventDefinition definition(String id, String cron) {
        StageDefinition stage = new StageDefinition("one", Duration.ZERO, Duration.ofSeconds(1), List.of(), List.of());
        return new EventDefinition(
                id, id, new RecurringSchedule(cron, ZoneId.of("UTC")), TargetSelector.ALL_ONLINE, List.of(stage));
    }
}
