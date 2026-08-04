package com.iantapply.orchestra.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Small compatibility layer for Paper and Folia scheduler behavior. */
public final class FoliaSupport {
    private FoliaSupport() {}

    /**
     * Detects the active scheduler implementation.
     *
     * @return whether Folia's regionized server class is present
     */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Executes work through the global-region scheduler available on Paper and Folia.
     *
     * @param plugin owning plugin
     * @param task work to execute
     */
    public static void executeGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }
}
