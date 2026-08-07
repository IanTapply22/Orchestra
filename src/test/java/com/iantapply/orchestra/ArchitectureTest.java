package com.iantapply.orchestra;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final Path SOURCES = Path.of("src/main/java/com/iantapply/orchestra");

    @Test
    void engineDoesNotDependOnInfrastructureOrPlatforms() throws IOException {
        assertNoImports(
                SOURCES.resolve("engine"),
                "com.iantapply.orchestra.adapter",
                "com.iantapply.orchestra.platform",
                "com.iantapply.orchestra.web",
                "org.bukkit",
                "com.velocitypowered");
    }

    @Test
    void apiDomainAndPortsDoNotDependOnOuterLayers() throws IOException {
        for (String packageName : List.of("api", "domain", "port")) {
            assertNoImports(
                    SOURCES.resolve(packageName),
                    "com.iantapply.orchestra.adapter",
                    "com.iantapply.orchestra.engine",
                    "com.iantapply.orchestra.platform",
                    "com.iantapply.orchestra.schedule",
                    "com.iantapply.orchestra.web",
                    "org.bukkit",
                    "com.velocitypowered");
        }
    }

    private static void assertNoImports(Path sourceRoot, String... forbiddenPrefixes) throws IOException {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String value = line.strip();
                    if (!value.startsWith("import ")) continue;
                    for (String prefix : forbiddenPrefixes) {
                        if (value.startsWith("import " + prefix)) {
                            violations.add(file + ": " + value);
                        }
                    }
                }
            }
        }
        if (!violations.isEmpty()) fail(String.join(System.lineSeparator(), violations));
    }
}
