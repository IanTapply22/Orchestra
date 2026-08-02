package com.iantapply.orchestra.adapter.postgres;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** Applies the bundled, idempotent Orchestra PostgreSQL schema migration. */
public final class MigrationRunner {
    private static final String MIGRATION = "/db/migration/V001__orchestra_core.sql";
    private final DataSource dataSource;

    /**
     * Creates a migration runner.
     *
     * @param dataSource database receiving the schema migration
     */
    public MigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Applies all bundled schema statements in one transaction.
     *
     * @throws SQLException when PostgreSQL rejects the migration
     * @throws IOException when the bundled migration cannot be read
     */
    public void migrate() throws SQLException, IOException {
        String sql;
        try (var input = MigrationRunner.class.getResourceAsStream(MIGRATION)) {
            if (input == null) throw new IOException("Missing migration: " + MIGRATION);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(sql);
                statement.executeUpdate("INSERT INTO orchestra_schema_history(version) VALUES (1) ON CONFLICT DO NOTHING");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }
}
