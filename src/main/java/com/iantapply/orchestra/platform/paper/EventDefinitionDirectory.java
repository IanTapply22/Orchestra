package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.yaml.YamlEventLoader;
import com.iantapply.orchestra.port.DefinitionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.java.JavaPlugin;

/** Installs bundled examples and loads event YAML files from the plugin data directory. */
@RequiredArgsConstructor
final class EventDefinitionDirectory {
    private static final List<String> BUNDLED_EXAMPLES = List.of("weekend_double_xp.yml");

    private final JavaPlugin plugin;

    /** Loads every valid YAML definition while logging and skipping invalid files. */
    int loadInto(DefinitionRepository repository) {
        Path directory = plugin.getDataFolder().toPath().resolve("events");
        try {
            Files.createDirectories(directory);
            installExamples(directory);
            return loadYamlFiles(directory, repository);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load event definitions", failure);
        }
    }

    private void installExamples(Path directory) throws IOException {
        for (String fileName : BUNDLED_EXAMPLES) {
            installExample(directory, fileName);
        }
    }

    private void installExample(Path directory, String fileName) throws IOException {
        Path destination = directory.resolve(fileName);
        if (Files.exists(destination)) {
            return;
        }

        try (var input = plugin.getResource("events/" + fileName)) {
            if (input != null) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private int loadYamlFiles(Path directory, DefinitionRepository repository) throws IOException {
        YamlEventLoader loader = new YamlEventLoader();
        int loaded = 0;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(this::isYaml).sorted().toList()) {
                try {
                    repository.save(loader.load(path));
                    loaded++;
                } catch (Exception failure) {
                    plugin.getLogger().severe("Cannot load " + path.getFileName() + ": " + failure.getMessage());
                }
            }
        }
        return loaded;
    }

    private boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
