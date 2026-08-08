package com.iantapply.orchestra.platform.paper;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds a small, secret-free snapshot of the active Paper runtime. */
final class PaperDiagnostics {
    private PaperDiagnostics() {}

    static List<String> create(JavaPlugin plugin, PaperSettings settings) {
        return List.of(
                "version=" + plugin.getPluginMeta().getVersion(),
                "server=" + Bukkit.getVersion(),
                "java=" + System.getProperty("java.version"),
                "folia=" + FoliaSupport.isFolia(),
                "postgres=" + enabled(settings.postgresEnabled()),
                "redis=" + enabled(settings.redisEnabled()),
                "web=" + enabled(settings.webEnabled()),
                "data-directory=" + plugin.getDataFolder().getAbsolutePath());
    }

    private static String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
