package com.iantapply.orchestra.adapter.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class MigrationRunnerTest {
    @Test
    void appliesBundledMigrationIdempotently() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE DOMAIN timestamptz AS timestamp with time zone");
        }

        MigrationRunner runner = new MigrationRunner(new H2CompatibleDataSource(dataSource));
        runner.migrate();
        runner.migrate();

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM orchestra_schema_history WHERE version=1")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }

    /** Removes PostgreSQL's partial-index predicate, which H2 does not implement. */
    private record H2CompatibleDataSource(DataSource delegate) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private static Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if ("createStatement".equals(method.getName()) && result instanceof Statement statement) {
                                return wrap(statement);
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private static Statement wrap(Statement statement) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (proxy, method, arguments) -> {
                        if ("execute".equals(method.getName())
                                && arguments != null
                                && arguments.length > 0
                                && arguments[0] instanceof String sql) {
                            arguments[0] =
                                    sql.replace("    WHERE status IN ('SCHEDULED', 'STARTING', 'RUNNING');", ";");
                        }
                        try {
                            return method.invoke(statement, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return delegate.isWrapperFor(type);
        }
    }
}
