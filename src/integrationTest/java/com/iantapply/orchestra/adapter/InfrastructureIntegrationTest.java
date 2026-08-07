package com.iantapply.orchestra.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.adapter.postgres.MigrationRunner;
import com.iantapply.orchestra.adapter.postgres.PostgresExecutionRepository;
import com.iantapply.orchestra.adapter.redis.RedisDistributedLock;
import com.iantapply.orchestra.domain.EventExecution;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"));

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Test
    void migrationsAndExecutionCompareAndSetWorkOnPostgresql() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            new MigrationRunner(dataSource).migrate();
            PostgresExecutionRepository repository = new PostgresExecutionRepository(dataSource);
            EventExecution initial = EventExecution.scheduled(
                            UUID.randomUUID(), "integration_event", Instant.EPOCH, Instant.EPOCH)
                    .withVariables(Map.of("source", "postgres"), Instant.EPOCH);

            repository.create(initial);
            EventExecution replacement = initial.withVariables(Map.of("source", "updated"), Instant.EPOCH);
            assertTrue(repository.compareAndSet(initial.version(), replacement));
            assertEquals(
                    "updated",
                    repository.find(initial.id()).orElseThrow().variables().get("source"));
        }
    }

    @Test
    void redisLeaseCanRenewAndReleaseItsOwnershipToken() {
        URI uri = URI.create("redis://127.0.0.1:" + REDIS.getMappedPort(6379) + "/0");
        RedisDistributedLock locks = new RedisDistributedLock(uri, "integration");

        var first = locks.tryAcquire("event", Duration.ofSeconds(2)).orElseThrow();
        assertTrue(first.renew(Duration.ofSeconds(3)));
        assertTrue(locks.tryAcquire("event", Duration.ofSeconds(2)).isEmpty());
        first.close();
        assertTrue(locks.tryAcquire("event", Duration.ofSeconds(2)).isPresent());
    }
}
