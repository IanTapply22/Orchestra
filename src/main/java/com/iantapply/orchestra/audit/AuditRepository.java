package com.iantapply.orchestra.audit;

import java.util.List;

/** Stores immutable audit entries in newest-first query order. */
public interface AuditRepository {
    /**
     * Persists an audit entry.
     *
     * @param entry entry to persist
     */
    void append(AuditEntry entry);

    /**
     * Returns the most recent entries, newest first.
     *
     * @param limit maximum number of entries
     * @return immutable or independently mutable result list
     */
    List<AuditEntry> recent(int limit);
}
