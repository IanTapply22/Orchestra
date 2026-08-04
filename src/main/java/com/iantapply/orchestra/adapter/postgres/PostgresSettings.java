package com.iantapply.orchestra.adapter.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;

/**
 * Validated PostgreSQL connection-pool settings.
 *
 * @param jdbcUrl PostgreSQL JDBC URL
 * @param username database username
 * @param password database password
 * @param maximumPoolSize maximum number of pooled connections
 */
public record PostgresSettings(String jdbcUrl, String username, String password, int maximumPoolSize) {
    /** Validates the JDBC URL and normalizes the pool size. */
    public PostgresSettings {
        if (!jdbcUrl.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("A PostgreSQL JDBC URL is required");
        if (maximumPoolSize < 1) throw new IllegalArgumentException("maximumPoolSize must be positive");
    }

    /**
     * Opens a configured connection pool.
     *
     * @return newly opened HikariCP data source owned by the caller
     */
    public HikariDataSource openDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("Orchestra-Postgres");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        config.setValidationTimeout(Duration.ofSeconds(2).toMillis());
        config.setKeepaliveTime(Duration.ofSeconds(30).toMillis());
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        return new HikariDataSource(config);
    }
}
