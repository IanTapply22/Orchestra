package com.iantapply.orchestra.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Thread-safe bounded audit repository for local operation and tests. */
public final class InMemoryAuditRepository implements AuditRepository {
    private final int capacity;
    private final ArrayDeque<AuditEntry> entries;
    /**
     * Creates a repository that evicts the oldest entry when full.
     *
     * @param capacity maximum retained entries; values below one become one
     */
    public InMemoryAuditRepository(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(this.capacity);
    }

    @Override
    public synchronized void append(AuditEntry entry) {
        if (entries.size() == capacity) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    @Override
    public synchronized List<AuditEntry> recent(int limit) {
        List<AuditEntry> values = new ArrayList<>(entries);
        java.util.Collections.reverse(values);
        return List.copyOf(values.subList(0, Math.min(values.size(), Math.max(0, limit))));
    }
}
