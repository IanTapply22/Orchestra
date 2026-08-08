package com.iantapply.orchestra.audit;

import java.time.Instant;

/**
 * Immutable record of an operator or API operation.
 *
 * @param occurredAt time at which the operation occurred
 * @param actor authenticated actor identifier
 * @param action operation name
 * @param resource affected resource identifier
 * @param detail human-readable operation detail
 * @param remoteAddress request origin, when available
 */
public record AuditEntry(
        Instant occurredAt, String actor, String action, String resource, String detail, String remoteAddress) {}
