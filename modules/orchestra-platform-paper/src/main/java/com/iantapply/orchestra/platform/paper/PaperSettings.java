package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.postgres.PostgresSettings;
import com.iantapply.orchestra.configuration.ConfigurationValueResolver;
import com.iantapply.orchestra.engine.EngineOptions;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated Paper configuration, including process and secret-file overrides. */
final class PaperSettings {
    private final FileConfiguration configuration;
    private final ConfigurationValueResolver values;

    PaperSettings(FileConfiguration configuration, Path dataDirectory) {
        this.configuration = Objects.requireNonNull(configuration);
        values = new ConfigurationValueResolver(dataDirectory);
    }

    boolean postgresEnabled() {
        return configuration.getBoolean("postgres.enabled");
    }

    PostgresSettings postgres() {
        String password = values.require(
                "postgres.password",
                null,
                configuration.getString("postgres.password-environment-variable"),
                configuration.getString("postgres.password-file"),
                configuration.getString("postgres.password"));
        if (password.equals("change-me")) {
            throw new IllegalArgumentException("Missing secure value for postgres.password");
        }
        return new PostgresSettings(
                configuration.getString("postgres.jdbc-url", ""),
                configuration.getString("postgres.username", ""),
                password,
                configuration.getInt("postgres.maximum-pool-size", 8));
    }

    boolean redisEnabled() {
        return configuration.getBoolean("redis.enabled");
    }

    URI redisUri() {
        String raw = values.require(
                "redis.uri",
                null,
                configuration.getString("redis.uri-environment-variable"),
                configuration.getString("redis.uri-file"),
                configuration.getString("redis.uri"));
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("redis.uri is invalid", failure);
        }
    }

    String redisNamespace() {
        return configuration.getString("redis.namespace", "orchestra");
    }

    ServerIdentity serverIdentity() {
        return ServerIdentity.from(configuration);
    }

    EngineOptions engineOptions() {
        int defaultWorkers = Math.min(4, Runtime.getRuntime().availableProcessors());
        return new EngineOptions(
                Math.max(1, configuration.getInt("engine.workers", defaultWorkers)),
                Math.max(16, configuration.getInt("engine.queue-capacity", 256)),
                Duration.ofMillis(Math.max(50, configuration.getLong("engine.poll-interval-ms", 250))),
                Math.max(1, configuration.getInt("engine.poll-batch-size", 256)),
                Duration.ofSeconds(Math.max(10, configuration.getLong("engine.lease-seconds", 600))),
                Duration.ofSeconds(Math.max(1, configuration.getLong("engine.shutdown-seconds", 10))));
    }

    boolean webEnabled() {
        return configuration.getBoolean("web.enabled");
    }

    InetSocketAddress webAddress() {
        return new InetSocketAddress(
                configuration.getString("web.bind", "127.0.0.1"), configuration.getInt("web.port", 8787));
    }

    Map<String, Actor> apiTokens() {
        Map<String, Actor> result = new HashMap<>();
        var section = configuration.getConfigurationSection("web.tokens");
        if (section != null) {
            section.getValues(false).forEach((token, roleName) -> addToken(result, token, roleName));
        }
        values.resolve(
                        configuration.getString("web.token-environment-variable"),
                        configuration.getString("web.token-file"),
                        null)
                .ifPresent(token -> addToken(result, token, Role.ADMINISTRATOR.name()));
        return Map.copyOf(result);
    }

    private static void addToken(Map<String, Actor> destination, String token, Object roleName) {
        if (token.length() < 24 || token.startsWith("replace-with")) {
            throw new IllegalArgumentException("Web bearer tokens must contain at least 24 characters");
        }
        String actorId = "api:" + UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8));
        Role role = Role.valueOf(String.valueOf(roleName).toUpperCase(Locale.ROOT));
        destination.put(token, new Actor(actorId, role));
    }
}
