package com.iantapply.orchestra.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Resolves configured values consistently from system properties, environment, files, and inline values. */
public final class ConfigurationValueResolver {
    private final Path baseDirectory;
    private final Map<String, String> environment;
    private final Function<String, String> systemProperties;

    /**
     * Uses the current process environment and system properties.
     *
     * @param baseDirectory directory used to resolve relative secret-file paths
     */
    public ConfigurationValueResolver(Path baseDirectory) {
        this(baseDirectory, System.getenv(), System::getProperty);
    }

    /**
     * Creates a resolver with injectable process sources for platforms and tests.
     *
     * @param baseDirectory directory used to resolve relative secret-file paths
     * @param environment environment-variable snapshot
     * @param systemProperties system-property lookup
     */
    public ConfigurationValueResolver(
            Path baseDirectory, Map<String, String> environment, Function<String, String> systemProperties) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.environment = Map.copyOf(environment);
        this.systemProperties = systemProperties;
    }

    /**
     * Resolves the first nonblank value using system property, environment, file, then inline precedence.
     *
     * @param systemProperty optional system-property name
     * @param environmentVariable optional environment-variable name
     * @param secretFile optional absolute path or path relative to the configured base directory
     * @param configuredValue optional inline value
     * @return selected stripped value
     */
    public Optional<String> resolve(
            String systemProperty, String environmentVariable, String secretFile, String configuredValue) {
        Optional<String> systemValue = namedValue(systemProperty, systemProperties);
        if (systemValue.isPresent()) return systemValue;
        Optional<String> environmentValue = namedValue(environmentVariable, environment::get);
        if (environmentValue.isPresent()) return environmentValue;
        Optional<String> fileValue = readSecretFile(secretFile);
        return fileValue.isPresent() ? fileValue : nonBlank(configuredValue);
    }

    /**
     * Resolves environment, file, then inline precedence without a system-property override.
     *
     * @param environmentVariable optional environment-variable name
     * @param secretFile optional secret-file path
     * @param configuredValue optional inline value
     * @return selected stripped value
     */
    public Optional<String> resolve(String environmentVariable, String secretFile, String configuredValue) {
        return resolve(null, environmentVariable, secretFile, configuredValue);
    }

    /**
     * Resolves a required value or reports its configuration label.
     *
     * @param label user-facing setting label
     * @param systemProperty optional system-property name
     * @param environmentVariable optional environment-variable name
     * @param secretFile optional secret-file path
     * @param configuredValue optional inline value
     * @return selected nonblank value
     */
    public String require(
            String label,
            String systemProperty,
            String environmentVariable,
            String secretFile,
            String configuredValue) {
        return resolve(systemProperty, environmentVariable, secretFile, configuredValue)
                .orElseThrow(() -> new IllegalArgumentException("Missing secure value for " + label));
    }

    /**
     * Reads an optional UTF-8 secret file, resolving relative paths against the base directory.
     *
     * @param configuredPath optional secret-file path
     * @return stripped file content, or empty when no path is configured
     */
    public Optional<String> readSecretFile(String configuredPath) {
        Optional<String> pathValue = nonBlank(configuredPath);
        if (pathValue.isEmpty()) return Optional.empty();
        Path path = Path.of(pathValue.get());
        if (!path.isAbsolute()) path = baseDirectory.resolve(path);
        path = path.normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Secret file is not a regular file: " + path);
        }
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) throw new IllegalArgumentException("Secret file is empty: " + path);
            return Optional.of(value);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not read secret file: " + path, failure);
        }
    }

    private static Optional<String> namedValue(String name, Function<String, String> source) {
        return nonBlank(name).flatMap(key -> nonBlank(source.apply(key)));
    }

    private static Optional<String> nonBlank(String value) {
        if (value == null) return Optional.empty();
        String stripped = value.strip();
        return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
    }
}
