package com.iantapply.orchestra.platform.paper.action;

import com.iantapply.orchestra.platform.paper.FoliaSupport;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Bridges asynchronous engine work onto Paper's or Folia's global scheduler. */
final class MainThreadExecutor {
    private final Plugin plugin;

    MainThreadExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Submits an operation and exposes its result without blocking the engine worker. */
    <T> CompletableFuture<T> submit(Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> complete(result, operation);
        if (Bukkit.isPrimaryThread()) task.run();
        else FoliaSupport.executeGlobal(plugin, task);
        return result;
    }

    private static <T> void complete(CompletableFuture<T> result, Callable<T> operation) {
        try {
            result.complete(operation.call());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
