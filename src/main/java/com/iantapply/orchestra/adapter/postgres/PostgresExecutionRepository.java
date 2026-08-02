package com.iantapply.orchestra.adapter.postgres;

import com.iantapply.orchestra.api.EventStatus;
import com.iantapply.orchestra.domain.EventExecution;
import com.iantapply.orchestra.port.ExecutionRepository;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL execution repository with atomic version-checked updates. */
public final class PostgresExecutionRepository implements ExecutionRepository {
    private static final String COLUMNS = "id, definition_id, status, stage_index, version, created_at, updated_at, "
            + "due_at, stage_started_at, variables, completed_actions, failure";
    private final DataSource dataSource;

    /**
     * Creates an execution repository.
     *
     * @param dataSource database connection pool
     */
    public PostgresExecutionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(EventExecution execution) {
        String sql = "INSERT INTO event_executions (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, execution);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw databaseFailure("create execution", failure);
        }
    }

    @Override
    public Optional<EventExecution> find(UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM event_executions WHERE id=?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseFailure("find execution", failure);
        }
    }

    @Override
    public Collection<EventExecution> findDue(Instant now, int limit) {
        return query("SELECT " + COLUMNS + " FROM event_executions "
                        + "WHERE due_at<=? AND status IN ('SCHEDULED','STARTING','RUNNING') ORDER BY due_at LIMIT ?",
                statement -> {
                    statement.setTimestamp(1, timestamp(now));
                    statement.setInt(2, limit);
                });
    }

    @Override
    public Collection<EventExecution> findActive(int limit) {
        return query("SELECT " + COLUMNS + " FROM event_executions "
                        + "WHERE status IN ('SCHEDULED','STARTING','RUNNING','PAUSED') ORDER BY updated_at LIMIT ?",
                statement -> statement.setInt(1, limit));
    }

    @Override
    public boolean compareAndSet(long expectedVersion, EventExecution replacement) {
        String sql = "UPDATE event_executions SET definition_id=?, status=?, stage_index=?, version=?, created_at=?, "
                + "updated_at=?, due_at=?, stage_started_at=?, variables=?, completed_actions=?, failure=? "
                + "WHERE id=? AND version=?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, replacement.definitionId());
            statement.setString(2, replacement.status().name());
            statement.setInt(3, replacement.stageIndex());
            statement.setLong(4, replacement.version());
            statement.setTimestamp(5, timestamp(replacement.createdAt()));
            statement.setTimestamp(6, timestamp(replacement.updatedAt()));
            setInstant(statement, 7, replacement.dueAt());
            setInstant(statement, 8, replacement.stageStartedAt());
            statement.setBytes(9, BinaryValueCodec.encodeMap(replacement.variables()));
            statement.setBytes(10, BinaryValueCodec.encodeStrings(replacement.completedActions()));
            statement.setString(11, replacement.failure());
            statement.setObject(12, replacement.id());
            statement.setLong(13, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw databaseFailure("replace execution", failure);
        }
    }

    private Collection<EventExecution> query(String sql, StatementBinder binder) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            List<EventExecution> result = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(rows));
            }
            return result;
        } catch (SQLException failure) {
            throw databaseFailure("query executions", failure);
        }
    }

    private static void bind(PreparedStatement statement, EventExecution value) throws SQLException {
        statement.setObject(1, value.id());
        statement.setString(2, value.definitionId());
        statement.setString(3, value.status().name());
        statement.setInt(4, value.stageIndex());
        statement.setLong(5, value.version());
        statement.setTimestamp(6, timestamp(value.createdAt()));
        statement.setTimestamp(7, timestamp(value.updatedAt()));
        setInstant(statement, 8, value.dueAt());
        setInstant(statement, 9, value.stageStartedAt());
        statement.setBytes(10, BinaryValueCodec.encodeMap(value.variables()));
        statement.setBytes(11, BinaryValueCodec.encodeStrings(value.completedActions()));
        statement.setString(12, value.failure());
    }

    private static EventExecution read(ResultSet rows) throws SQLException {
        return new EventExecution(rows.getObject("id", UUID.class), rows.getString("definition_id"),
                EventStatus.valueOf(rows.getString("status")), rows.getInt("stage_index"), rows.getLong("version"),
                instant(rows, "created_at"), instant(rows, "updated_at"), instant(rows, "due_at"),
                instant(rows, "stage_started_at"), BinaryValueCodec.decodeMap(rows.getBytes("variables")),
                BinaryValueCodec.decodeStrings(rows.getBytes("completed_actions")), rows.getString("failure"));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rows, String name) throws SQLException {
        Timestamp value = rows.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        else statement.setTimestamp(index, timestamp(value));
    }
    private static IllegalStateException databaseFailure(String operation, SQLException cause) {
        return new IllegalStateException("Could not " + operation, cause);
    }
    /** Binds query-specific parameters to a prepared statement. */
    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
