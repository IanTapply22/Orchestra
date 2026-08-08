package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.yaml.YamlEventLoader;
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
    private static final List<String> BUNDLED_EXAMPLES = List.of("weekend_double_xp.yml");

    private final JavaPlugin plugin;

    public EventDefinitionDirectory(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Loads all valid startup definitions and reports every invalid file. */
    public int loadInto(DefinitionRepository repository) {
        ValidationReport report = validate();
        report.errors().forEach(error -> plugin.getLogger().severe(error));
        report.definitions().forEach(repository::save);
        return report.definitions().size();
    }

    /** Validates every YAML file without changing runtime state. */
    public ValidationReport validate() {
        Path directory = plugin.getDataFolder().toPath().resolve("events");
        try {
            Files.createDirectories(directory);
            installExamples(directory);
            return validateDirectory(directory);
        } catch (IOException failure) {
            return new ValidationReport(List.of(), List.of("Cannot read event directory: " + failure.getMessage()));
        }
    }

    /** Replaces runtime definitions only when every file is valid. */
    public ValidationReport reloadInto(DefinitionRepository repository) {
        ValidationReport report = validate();
        if (report.valid()) repository.replaceAll(report.definitions());
        return report;
    }

    static ValidationReport validateDirectory(Path directory) throws IOException {
        YamlEventLoader loader = new YamlEventLoader();
        List<EventDefinition> definitions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (!Files.isDirectory(directory)) {
            return new ValidationReport(List.of(), List.of("Event directory does not exist: " + directory));
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
        return new ValidationReport(List.copyOf(definitions), List.copyOf(errors));
    }

    private void installExamples(Path directory) throws IOException {
        for (String fileName : BUNDLED_EXAMPLES) {
            Path destination = directory.resolve(fileName);
            if (Files.exists(destination)) continue;
            try (var input = plugin.getResource("events/" + fileName)) {
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

    public record ValidationReport(List<EventDefinition> definitions, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }

        public String summary() {
            if (valid()) return "Validated " + definitions.size() + " event definition(s)";
            return errors.size() + " validation error(s): " + String.join("; ", errors);
        }
    }
}
