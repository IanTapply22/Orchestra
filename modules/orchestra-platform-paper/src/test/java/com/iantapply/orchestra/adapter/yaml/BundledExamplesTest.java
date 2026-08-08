package com.iantapply.orchestra.adapter.yaml;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.iantapply.orchestra.schedule.RecurringEventScheduler;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class BundledExamplesTest {
    @TestFactory
    Stream<DynamicTest> everyBundledExampleIsValid() throws Exception {
        Path directory = examplesDirectory();
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                        var definition = new YamlEventLoader().load(path);
                        assertNotNull(definition);
                        RecurringEventScheduler.validateSchedule(definition);
                    }))
                    .toList()
                    .stream();
        }
    }

    private Path examplesDirectory() throws URISyntaxException {
        return Path.of(
                Objects.requireNonNull(getClass().getResource("/examples")).toURI());
    }
}
