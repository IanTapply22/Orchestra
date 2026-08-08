package com.iantapply.orchestra.adapter.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.EventStatus;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import com.iantapply.orchestra.domain.EventExecution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryStoresTest {
    @Test
    void storesDefinitionsAndRejectsDuplicateExecutions() {
        InMemoryStores stores = new InMemoryStores();
        EventDefinition definition = definition();
        EventExecution execution =
                EventExecution.scheduled(UUID.randomUUID(), definition.id(), Instant.EPOCH, Instant.EPOCH);

        stores.save(definition);
        stores.create(execution);

        assertEquals(definition, stores.find(definition.id()).orElseThrow());
        assertEquals(List.of(definition), List.copyOf(stores.findAll()));
        assertEquals(execution, stores.find(execution.id()).orElseThrow());
        assertThrows(IllegalStateException.class, () -> stores.create(execution));
    }

    @Test
    void dueAndActiveQueriesRespectStateTimeOrderAndLimits() {
        InMemoryStores stores = new InMemoryStores();
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        EventExecution later = EventExecution.scheduled(UUID.randomUUID(), "event", now, now.minusSeconds(1));
        EventExecution earlier = EventExecution.scheduled(UUID.randomUUID(), "event", now, now.minusSeconds(2));
        EventExecution future = EventExecution.scheduled(UUID.randomUUID(), "event", now, now.plusSeconds(1));
        EventExecution paused = EventExecution.scheduled(UUID.randomUUID(), "event", now, now)
                .transition(EventStatus.PAUSED, -1, now, null);
        stores.create(later);
        stores.create(earlier);
        stores.create(future);
        stores.create(paused);

        assertEquals(
                List.of(earlier.id(), later.id()),
                stores.findDue(now, 2).stream().map(EventExecution::id).toList());
        assertEquals(4, stores.findActive(10).size());
    }

    @Test
    void compareAndSetUsesObservedVersion() {
        InMemoryStores stores = new InMemoryStores();
        EventExecution original = EventExecution.scheduled(UUID.randomUUID(), "event", Instant.EPOCH, Instant.EPOCH);
        stores.create(original);
        EventExecution replacement = original.withVariables(Map.of("x", 1), Instant.EPOCH);

        assertFalse(stores.compareAndSet(99, replacement));
        assertTrue(stores.compareAndSet(0, replacement));
        assertEquals(replacement, stores.find(original.id()).orElseThrow());
    }

    @Test
    void leasesAreExclusiveUntilClosedOrExpired() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        InMemoryStores stores = new InMemoryStores(clock);
        var first = stores.tryAcquire("event", Duration.ofSeconds(1)).orElseThrow();

        assertTrue(stores.tryAcquire("event", Duration.ofSeconds(1)).isEmpty());
        first.close();
        assertTrue(stores.tryAcquire("event", Duration.ofSeconds(1)).isPresent());

        var shortLease = stores.tryAcquire("short", Duration.ofMillis(1)).orElseThrow();
        clock.advance(Duration.ofMillis(2));
        assertTrue(stores.tryAcquire("short", Duration.ofSeconds(1)).isPresent());
        shortLease.close();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static EventDefinition definition() {
        StageDefinition stage = new StageDefinition("one", Duration.ZERO, Duration.ofSeconds(1), List.of(), List.of());
        return new EventDefinition("test_event", "Test", TargetSelector.ALL_ONLINE, List.of(stage));
    }
}
