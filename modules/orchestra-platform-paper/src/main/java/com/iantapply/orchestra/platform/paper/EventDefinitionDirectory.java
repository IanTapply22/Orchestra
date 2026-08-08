package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.yaml.YamlEventLoader;
import com.iantapply.orchestra.administration.DefinitionValidationReport;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.schedule.RecurringEventScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;

/** Installs bundled examples and validates atomically reloadable event YAML files. */
public final class EventDefinitionDirectory {
    private static final List<String> BUNDLED_EVENTS = List.of("weekend_double_xp.yml");
    private static final List<String> BUNDLED_LIBRARY = List.of(
            "maintenance_countdown.yml",
            "weekend_multiplier.yml",
            "cross_server_announcement.yml",
            "scheduled_restart.yml",
            "conditional_event_with_retries.yml");

    private final JavaPlugin plugin;

    /**
     * Creates a definition directory owned by a Paper plugin.
     *
     * @param plugin owning plugin
     */
    public EventDefinitionDirectory(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads all valid startup definitions and reports every invalid file.
     *
     * @param repository destination repository
     * @return number of loaded definitions
     */
    public int loadInto(DefinitionRepository repository) {
        DefinitionValidationReport report = validate();
        report.errors().forEach(error -> plugin.getLogger().severe(error));
        report.definitions().forEach(repository::save);
        return report.definitions().size();
    }

    /**
     * Validates every YAML file without changing runtime state.
     *
     * @return immutable validation report
     */
    public DefinitionValidationReport validate() {
        Path directory = plugin.getDataFolder().toPath().resolve("events");
        try {
            Files.createDirectories(directory);
            installResources(directory, "events/", BUNDLED_EVENTS);
            Path examples = plugin.getDataFolder().toPath().resolve("examples");
            Files.createDirectories(examples);
            installResources(examples, "examples/", BUNDLED_LIBRARY);
            return validateDirectory(directory);
        } catch (IOException failure) {
            return new DefinitionValidationReport(
                    List.of(), List.of("Cannot read event directory: " + failure.getMessage()));
        }
    }

    static DefinitionValidationReport validateDirectory(Path directory) throws IOException {
        YamlEventLoader loader = new YamlEventLoader();
        List<EventDefinition> definitions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (!Files.isDirectory(directory)) {
            return new DefinitionValidationReport(List.of(), List.of("Event directory does not exist: " + directory));
        }
        try (var paths = Files.list(directory)) {
            for (Path path :
                    paths.filter(EventDefinitionDirectory::isYaml).sorted().toList()) {
                try {
                    EventDefinition definition = loader.load(path);
                    RecurringEventScheduler.validateSchedule(definition);
                    if (!ids.add(definition.id())) {
                        errors.add(path.getFileName() + ": duplicate event ID '" + definition.id() + "'");
                    } else {
                        definitions.add(definition);
                    }
                } catch (Exception failure) {
                    errors.add(path.getFileName() + ": " + failure.getMessage());
                }
            }
        }
        return new DefinitionValidationReport(definitions, errors);
    }

    private void installResources(Path directory, String resourceDirectory, List<String> fileNames) throws IOException {
        for (String fileName : fileNames) {
            Path destination = directory.resolve(fileName);
            if (Files.exists(destination)) continue;
            try (var input = plugin.getResource(resourceDirectory + fileName)) {
                if (input != null) Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isYaml(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
