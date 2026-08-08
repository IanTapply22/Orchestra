package com.iantapply.orchestra.adapter;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.adapter.postgres.MigrationRunner;
import com.iantapply.orchestra.adapter.postgres.PostgresAuditRepository;
import com.iantapply.orchestra.adapter.postgres.PostgresExecutionRepository;
import com.iantapply.orchestra.adapter.redis.RedisDistributedLock;
import com.iantapply.orchestra.adapter.redis.RedisTransport;
import com.iantapply.orchestra.audit.AuditEntry;
import com.iantapply.orchestra.domain.EventExecution;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withCommand("redis-server", "--requirepass", "integration-secret")
            .withExposedPorts(6379);

    @Test
    void migrationsAndExecutionCompareAndSetWorkOnPostgresql() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            new MigrationRunner(dataSource).migrate();
            PostgresExecutionRepository repository = new PostgresExecutionRepository(dataSource);
            Instant persistedAt = Instant.parse("2026-08-04T12:34:56.123456Z");
            EventExecution initial = EventExecution.scheduled(
                            UUID.randomUUID(), "integration_event", persistedAt, persistedAt)
                    .withVariables(
                            Map.of("source", "postgres", "nested", Map.of("items", List.of("one", 2, true))),
                            persistedAt);

            repository.create(initial);
            EventExecution replacement = initial.withVariables(
                    Map.of("source", "updated", "nested", Map.of("items", List.of("one", 2, true))), persistedAt);
            assertTrue(repository.compareAndSet(initial.version(), replacement));
            EventExecution loaded = repository.find(initial.id()).orElseThrow();
            assertEquals("updated", loaded.variables().get("source"));
            assertEquals(replacement.variables(), loaded.variables());
            assertEquals(persistedAt, loaded.updatedAt());

            PostgresAuditRepository audit = new PostgresAuditRepository(dataSource);
            AuditEntry entry = new AuditEntry(persistedAt, "integration", "update", "event", "detail", "local");
            audit.append(entry);
            assertEquals(entry, audit.recent(1).getFirst());
        }
    }

    @Test
    void redisLeaseCanRenewAndReleaseItsOwnershipToken() {
        URI uri = redisUri();
        RedisDistributedLock locks = new RedisDistributedLock(uri, "integration");

        var first = locks.tryAcquire("event", Duration.ofMillis(100)).orElseThrow();
        assertTrue(first.renew(Duration.ofSeconds(3)));
        assertTrue(locks.tryAcquire("event", Duration.ofSeconds(2)).isEmpty());
        first.close();
        assertTrue(locks.tryAcquire("event", Duration.ofSeconds(2)).isPresent());

        var expiring = locks.tryAcquire("expiring", Duration.ofMillis(100)).orElseThrow();
        AtomicReference<com.iantapply.orchestra.port.DistributedLock.Lease> replacement = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            var acquired = locks.tryAcquire("expiring", Duration.ofSeconds(2));
            acquired.ifPresent(replacement::set);
            return acquired.isPresent();
        });
        expiring.close();
        assertTrue(locks.tryAcquire("expiring", Duration.ofSeconds(2)).isEmpty());
        replacement.get().close();
    }

    @Test
    void redisPubSubReconnectsAfterServerRestart() {
        AtomicInteger reconnects = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        try (RedisTransport transport =
                new RedisTransport(redisUri(), "integration", ignored -> reconnects.incrementAndGet())) {
            transport.subscribe("events", ignored -> messages.incrementAndGet());
            await().atMost(Duration.ofSeconds(5)).until(() -> publishUntilReceived(transport, messages, 1));

            REDIS.getDockerClient().restartContainerCmd(REDIS.getContainerId()).exec();
            await().atMost(Duration.ofSeconds(10)).until(() -> reconnects.get() > 0);
            await().atMost(Duration.ofSeconds(10)).until(() -> publishUntilReceived(transport, messages, 2));
        }
    }

    private static boolean publishUntilReceived(RedisTransport transport, AtomicInteger messages, int expected) {
        try {
            transport.publish("events", new byte[] {(byte) expected});
        } catch (IllegalStateException ignored) {
            return false;
        }
        return messages.get() >= expected;
    }

    private static URI redisUri() {
        return URI.create("redis://default:integration-secret@127.0.0.1:" + REDIS.getMappedPort(6379) + "/0");
    }
}
