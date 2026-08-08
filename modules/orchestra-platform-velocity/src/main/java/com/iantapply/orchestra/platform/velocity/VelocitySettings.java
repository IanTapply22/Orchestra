package com.iantapply.orchestra.platform.velocity;

import com.iantapply.orchestra.configuration.ConfigurationValueResolver;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Validated Velocity configuration with environment and secret-file overrides. */
record VelocitySettings(String proxyId, URI redisUri, String redisNamespace) {
    private static final String CONFIGURATION_FILE = "orchestra.properties";
    private static final String DEFAULT_CONFIGURATION_RESOURCE = "/orchestra.properties";

    static VelocitySettings load(Path dataDirectory) throws IOException {
        return load(dataDirectory, System.getenv());
    }

    static VelocitySettings load(Path dataDirectory, Map<String, String> environment) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configuration = dataDirectory.resolve(CONFIGURATION_FILE);
        if (Files.notExists(configuration)) copyDefaultConfiguration(configuration);

        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(configuration)) {
            values.load(reader);
        }

        ConfigurationValueResolver resolver =
                new ConfigurationValueResolver(dataDirectory, environment, System::getProperty);
        String proxyId = resolver.resolve("orchestra.proxy.id", null, null, values.getProperty("proxy.id"))
                .orElse("");
        validateProxyId(proxyId);
        String namespace = resolver.resolve(
                        "orchestra.redis.namespace", null, null, values.getProperty("redis.namespace"))
                .orElse("");
        if (namespace.isBlank() || namespace.length() > 128) {
            throw new IllegalArgumentException("redis.namespace must contain between 1 and 128 characters");
        }

        String environmentName = values.getProperty("redis.uri-environment-variable", "ORCHESTRA_REDIS_URI")
                .trim();
        String secretFile = values.getProperty("redis.uri-file", "").trim();
        String redisValue = resolver.require(
                "Redis URI", "orchestra.redis.uri", environmentName, secretFile, values.getProperty("redis.uri"));

        URI redisUri;
        try {
            redisUri = URI.create(redisValue);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Redis URI is invalid", invalid);
        }
        validateRedisUri(redisUri);
        return new VelocitySettings(proxyId, redisUri, namespace);
    }

    private static void copyDefaultConfiguration(Path destination) throws IOException {
        try (var input = VelocitySettings.class.getResourceAsStream(DEFAULT_CONFIGURATION_RESOURCE)) {
            if (input == null) {
                throw new IOException("Packaged Velocity configuration is missing: " + DEFAULT_CONFIGURATION_RESOURCE);
            }
            Files.copy(input, destination);
        }
    }

    private static void validateProxyId(String proxyId) {
        if (proxyId == null || !proxyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException(
                    "proxy.id must contain 1-64 letters, digits, dots, underscores, or hyphens");
        }
    }

    private static void validateRedisUri(URI uri) {
        if (!"redis".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Redis URI must use the redis:// scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Redis URI must include a host");
        }
        if (uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("Redis URI must not include a query or fragment");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("Redis URI port must be between 1 and 65535");
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank() && !path.matches("/[0-9]+")) {
            throw new IllegalArgumentException("Redis URI path must be a numeric database such as /0");
        }
    }
}
