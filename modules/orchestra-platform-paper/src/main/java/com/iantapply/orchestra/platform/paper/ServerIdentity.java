package com.iantapply.orchestra.platform.paper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable identity and labels of one Paper server.
 *
 * @param id unique network server name
 * @param groups server groups
 * @param tags server labels
 */
public record ServerIdentity(String id, Set<String> groups, Map<String, String> tags) {
    /** Snapshots group and tag collections. */
    public ServerIdentity {
        groups = Set.copyOf(groups);
        tags = Map.copyOf(tags);
    }

    /**
     * Loads a server identity from plugin configuration.
     *
     * @param config plugin configuration containing the {@code server} section
     * @return immutable configured server identity
     */
    public static ServerIdentity from(FileConfiguration config) {
        Set<String> groups = new HashSet<>(config.getStringList("server.groups"));
        Map<String, String> tags = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("server.tags");
        if (section != null) {
            section.getValues(false).forEach((key, value) -> tags.put(key, String.valueOf(value)));
        }
        return new ServerIdentity(config.getString("server.id", "paper-1"), groups, tags);
    }
}
