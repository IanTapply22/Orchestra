package com.iantapply.orchestra.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VelocitySettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDefaultsAndAcceptsAnEnvironmentSecret() throws Exception {
        VelocitySettings settings = VelocitySettings.load(
                temporaryDirectory, Map.of("ORCHESTRA_REDIS_URI", "redis://redis.internal:6380/2"));

        assertEquals("velocity-1", settings.proxyId());
        assertEquals("redis.internal", settings.redisUri().getHost());
        Path copiedConfiguration = temporaryDirectory.resolve("orchestra.properties");
        assertTrue(Files.exists(copiedConfiguration));
        assertTrue(Files.readString(copiedConfiguration).contains("# Orchestra Velocity configuration"));
    }

    @Test
    void readsRedisUriFromRelativeSecretFile() throws Exception {
        Files.createDirectories(temporaryDirectory);
        Files.writeString(temporaryDirectory.resolve("redis-uri.txt"), "redis://localhost:6379/4\n");
        Files.writeString(
                temporaryDirectory.resolve("orchestra.properties"),
                "proxy.id=proxy_a\nredis.uri-file=redis-uri.txt\nredis.namespace=network\n");

        VelocitySettings settings = VelocitySettings.load(temporaryDirectory, Map.of());

        assertEquals("/4", settings.redisUri().getPath());
    }

    @Test
    void rejectsInvalidProxyIdsAndRedisSchemes() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("orchestra.properties"),
                "proxy.id=not valid\nredis.uri=http://localhost:6379\nredis.namespace=network\n");

        assertThrows(IllegalArgumentException.class, () -> VelocitySettings.load(temporaryDirectory, Map.of()));
    }
}
