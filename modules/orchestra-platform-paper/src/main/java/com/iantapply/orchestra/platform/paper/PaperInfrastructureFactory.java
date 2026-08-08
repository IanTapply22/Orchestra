package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.adapter.postgres.MigrationRunner;
import com.iantapply.orchestra.adapter.postgres.PostgresAuditRepository;
import com.iantapply.orchestra.adapter.postgres.PostgresExecutionRepository;
import com.iantapply.orchestra.adapter.redis.RedisDistributedLock;
import com.iantapply.orchestra.adapter.redis.RedisTransport;
import com.iantapply.orchestra.audit.AuditRepository;
import com.iantapply.orchestra.audit.InMemoryAuditRepository;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.port.DistributedLock;
import com.iantapply.orchestra.port.ExecutionRepository;
import com.iantapply.orchestra.velocity.ProxyCommandPublisher;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.function.Consumer;

/** Creates Paper infrastructure adapters without coupling the plugin lifecycle to their details. */
final class PaperInfrastructureFactory {
    private final PaperSettings settings;
    private final MetricsRegistry metrics;
    private final Consumer<AutoCloseable> resources;

    PaperInfrastructureFactory(PaperSettings settings, MetricsRegistry metrics, Consumer<AutoCloseable> resources) {
        this.settings = settings;
        this.metrics = metrics;
        this.resources = resources;
    }

    PaperInfrastructure create() {
        InMemoryStores memory = new InMemoryStores();
        ExecutionRepository executions = memory;
        AuditRepository audit = new InMemoryAuditRepository(10_000);
        if (settings.postgresEnabled()) {
            HikariDataSource dataSource = openPostgres();
            executions = new PostgresExecutionRepository(dataSource);
            audit = new PostgresAuditRepository(dataSource);
        }

        DistributedLock locks = memory;
        ProxyCommandPublisher proxyCommands = null;
        if (settings.redisEnabled()) {
            URI redisUri = settings.redisUri();
            String redisNamespace = settings.redisNamespace();
            locks = new RedisDistributedLock(redisUri, redisNamespace);
            RedisTransport transport = new RedisTransport(redisUri, redisNamespace, metrics::increment);
            resources.accept(transport);
            proxyCommands = new ProxyCommandPublisher(transport);
        }
        return new PaperInfrastructure(memory, executions, locks, audit, proxyCommands);
    }

    private HikariDataSource openPostgres() {
        HikariDataSource dataSource = settings.postgres().openDataSource();
        try {
            new MigrationRunner(dataSource).migrate();
        } catch (Exception failure) {
            dataSource.close();
            throw new IllegalStateException("PostgreSQL migration failed", failure);
        }
        resources.accept(dataSource);
        return dataSource;
    }
}
