package com.iantapply.orchestra.adapter.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlEventLoaderTest {
    @TempDir
    Path directory;

    @Test
    void loadsSchedulesTargetsConditionsAndBothActionForms() throws Exception {
        Path file = write("event.yml", """
                schema-version: 1
                id: NETWORK_EVENT
                display-name: "Network Event"
                schedule:
                  cron: "*/5 * * * *"
                  timezone: "America/Toronto"
                targets:
                  servers: [survival-1]
                  groups: survival
                  tags:
                    region: na-east
                stages:
                  - id: opening
                    duration: 5m
                    timeout: 10s
                    conditions:
                      - type: online_players_at_least
                        count: 10
                    actions:
                      - broadcast: "<gold>Starting</gold>"
                      - id: multiplier
                        type: set_variable
                        key: xp_multiplier
                        value: 2
                        retry:
                          max-attempts: 4
                          initial-delay: 2s
                          multiplier: 1.5
                          maximum-delay: 8s
                """);

        var event = new YamlEventLoader().load(file);

        assertEquals("network_event", event.id());
        assertEquals("*/5 * * * *", event.schedule().cron());
        assertEquals(ZoneId.of("America/Toronto"), event.schedule().zone());
        assertEquals(Set.of("survival-1"), event.targets().servers());
        assertEquals(Set.of("survival"), event.targets().groups());
        assertEquals(Map.of("region", "na-east"), event.targets().tags());
        var stage = event.stages().getFirst();
        assertEquals(Duration.ofMinutes(5), stage.duration());
        assertEquals(10, stage.conditions().getFirst().arguments().get("count"));
        assertEquals("Starting", stripMarkup((String)
                stage.actions().getFirst().arguments().get("message")));
        assertEquals(4, stage.actions().get(1).retryPolicy().maxAttempts());
        assertEquals(2, stage.actions().get(1).arguments().get("value"));
    }

    @Test
    void supportsAllOnlineAndDefaultDisplayName() throws Exception {
        Path file = write("simple.yaml", """
                schema-version: 1
                id: simple_event
                targets:
                  all-online: true
                stages:
                  - id: only
                    actions: []
                """);

        var event = new YamlEventLoader().load(file);

        assertEquals("simple_event", event.displayName());
        assertTrue(event.targets().allOnline());
        assertEquals(Duration.ZERO, event.stages().getFirst().duration());
    }

    @Test
    void rejectsMissingFilesAndInvalidDefinitions() throws Exception {
        assertThrows(IOException.class, () -> new YamlEventLoader().load(directory.resolve("missing.yml")));
        Path invalid = write("invalid.yml", "schema-version: 1\ndisplay-name: Missing id\nstages: []\n");
        assertThrows(IllegalArgumentException.class, () -> new YamlEventLoader().load(invalid));
    }

    @Test
    void reportsPrecisePathsUnknownFieldsAndDuplicateIds() throws Exception {
        Path invalid = write("invalid-shapes.yml", """
                schema-version: 1
                id: invalid
                targets:
                  all-online: true
                stages:
                  - id: first
                    actions:
                      - id: repeated
                        type: broadcast
                        message: hello
                        retry:
                          max-attempts: nope
                """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> new YamlEventLoader().load(invalid));
        assertTrue(failure.getMessage().contains("stages[0].actions[0].retry.max-attempts"), failure::getMessage);

        Path unknown = write("unknown.yml", "schema-version: 1\nid: unknown\nextra: value\nstages: []\n");
        IllegalArgumentException unknownFailure =
                assertThrows(IllegalArgumentException.class, () -> new YamlEventLoader().load(unknown));
        assertTrue(unknownFailure.getMessage().contains("extra: unknown field"));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content);
    }

    private static String stripMarkup(String input) {
        return input.replace("<gold>", "").replace("</gold>", "");
    }
}
