package com.iantapply.orchestra.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationValueResolverTest {
    @TempDir
    Path directory;

    @Test
    void appliesDocumentedPrecedence() throws Exception {
        Files.writeString(directory.resolve("secret.txt"), "from-file\n");
        var resolver = new ConfigurationValueResolver(
                directory, Map.of("VALUE_ENV", "from-environment"), name -> "VALUE_SYS".equals(name) ? "system" : null);

        assertEquals("system", resolver.require("value", "VALUE_SYS", "VALUE_ENV", "secret.txt", "inline"));
        assertEquals("from-environment", resolver.require("value", null, "VALUE_ENV", "secret.txt", "inline"));
        assertEquals("from-file", resolver.require("value", null, null, "secret.txt", "inline"));
        assertEquals("inline", resolver.require("value", null, null, null, " inline "));
    }

    @Test
    void rejectsMissingEmptyAndInvalidSecretFiles() throws Exception {
        var resolver = new ConfigurationValueResolver(directory, Map.of(), ignored -> null);
        assertThrows(IllegalArgumentException.class, () -> resolver.require("database password", null, null, null, ""));
        assertThrows(IllegalArgumentException.class, () -> resolver.readSecretFile("missing.txt"));

        Files.writeString(directory.resolve("empty.txt"), " \n");
        assertThrows(IllegalArgumentException.class, () -> resolver.readSecretFile("empty.txt"));
    }
}
