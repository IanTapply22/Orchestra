package com.iantapply.orchestra.adapter.postgres;

import com.iantapply.orchestra.audit.AuditEntry;
import com.iantapply.orchestra.audit.AuditRepository;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL-backed audit repository. */
public final class PostgresAuditRepository implements AuditRepository {
    private final DataSource dataSource;

    /**
     * Creates an audit repository.
     *
     * @param dataSource database connection pool
     */
    public PostgresAuditRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void append(AuditEntry entry) {
        String sql = "INSERT INTO audit_log(occurred_at,actor,action,resource,detail,remote_address) VALUES (?,?,?,?,?,?)";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(entry.occurredAt()));
            statement.setString(2, entry.actor());
            statement.setString(3, entry.action());
            statement.setString(4, entry.resource());
            statement.setString(5, entry.detail());
            statement.setString(6, entry.remoteAddress());
            statement.executeUpdate();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not append audit entry", failure);
        }
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        String sql = "SELECT occurred_at,actor,action,resource,detail,remote_address FROM audit_log ORDER BY sequence DESC LIMIT ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.clamp(limit, 1, 1_000));
            List<AuditEntry> result = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new AuditEntry(
                            rows.getTimestamp(1).toInstant(),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6)));
                }
            }
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read audit entries", failure);
        }
    }
}
