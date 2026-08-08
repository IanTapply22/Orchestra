package com.iantapply.orchestra.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryAuditRepositoryTest {
    @Test
    void retainsCapacityAndReturnsNewestFirst() {
        InMemoryAuditRepository repository = new InMemoryAuditRepository(2);
        AuditEntry first = entry("first");
        AuditEntry second = entry("second");
        AuditEntry third = entry("third");

        repository.append(first);
        repository.append(second);
        repository.append(third);

        assertEquals(java.util.List.of(third, second), repository.recent(10));
        assertEquals(java.util.List.of(third), repository.recent(1));
        assertEquals(java.util.List.of(), repository.recent(-1));
    }

    private static AuditEntry entry(String action) {
        return new AuditEntry(Instant.EPOCH, "actor", action, "resource", "detail", "127.0.0.1");
    }
}
