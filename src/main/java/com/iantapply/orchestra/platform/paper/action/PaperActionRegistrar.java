package com.iantapply.orchestra.platform.paper.action;

import com.iantapply.orchestra.api.ActionContext;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.platform.paper.JoinGate;
import com.iantapply.orchestra.velocity.ProxyCommandPublisher;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Registers actions and conditions implemented by a Paper server agent. */
public final class PaperActionRegistrar {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final MainThreadExecutor mainThread;
    private final JoinGate joinGate;
    private final ProxyCommandPublisher proxyCommands;

    /**
     * Creates a registrar for one backend.
     *
     * @param plugin owning Paper plugin
     * @param joinGate server-local join gate controlled by event actions
     */
    public PaperActionRegistrar(Plugin plugin, JoinGate joinGate) {
        this(plugin, joinGate, null);
    }

    /**
     * Creates a registrar with optional Velocity command publishing.
     *
     * @param plugin owning Paper plugin
     * @param joinGate local join gate
     * @param proxyCommands proxy publisher, or {@code null} when Redis is disabled
     */
    public PaperActionRegistrar(Plugin plugin, JoinGate joinGate, ProxyCommandPublisher proxyCommands) {
        this.mainThread = new MainThreadExecutor(plugin);
        this.joinGate = joinGate;
        this.proxyCommands = proxyCommands;
    }

    /**
     * Registers built-in actions and conditions.
     *
     * @param registry registry receiving all built-in Paper actions and conditions
     */
    public void registerInto(ActionRegistry registry) {
        registerMessages(registry);
        registerOperations(registry);
        registerConditions(registry);
    }

    private void registerMessages(ActionRegistry registry) {
        registry.registerAction(
                "broadcast",
                context -> mainThread.submit(() -> {
                    Bukkit.broadcast(message(context, "message"));
                    return null;
                }));
        registry.registerAction(
                "title",
                context -> mainThread.submit(() -> {
                    Title title = Title.title(message(context, "message"), Component.empty());
                    Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(title));
                    return null;
                }));
        registry.registerAction(
                "action_bar",
                context -> mainThread.submit(() -> {
                    Component message = message(context, "message");
                    Bukkit.getOnlinePlayers().forEach(player -> player.sendActionBar(message));
                    return null;
                }));
    }

    private void registerOperations(ActionRegistry registry) {
        registry.registerAction(
                "command",
                context -> mainThread.submit(() -> {
                    String command = interpolate(context.getString("execute"), context);
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                        throw new IllegalArgumentException("Unknown command: " + command);
                    }
                    return null;
                }));
        registry.registerAction("toggle_joins", context -> {
            joinGate.setEnabled(Boolean.parseBoolean(context.getString("value")));
            return CompletableFuture.completedFuture(null);
        });
        registry.registerAction("discord_webhook", new DiscordWebhookAction());
        registry.registerAction("move_player", context -> {
            requireProxyCommands()
                    .movePlayer(context.getString("proxy"), context.getString("player"), context.getString("server"));
            return CompletableFuture.completedFuture(null);
        });
        registry.registerAction("toggle_group_joins", context -> {
            requireProxyCommands()
                    .setGroupJoins(
                            context.getString("proxy"),
                            context.getString("group"),
                            Boolean.parseBoolean(context.getString("enabled")));
            return CompletableFuture.completedFuture(null);
        });
    }

    private void registerConditions(ActionRegistry registry) {
        registry.registerCondition(
                "online_players_at_least",
                context -> CompletableFuture.completedFuture(Bukkit.getOnlinePlayers()
                                .size()
                        >= integerArgument(context.condition().arguments().get("count"))));
        registry.registerCondition(
                "variable_equals",
                context -> CompletableFuture.completedFuture(Objects.equals(
                        context.variables()
                                .get(String.valueOf(
                                        context.condition().arguments().get("key"))),
                        context.condition().arguments().get("value"))));
    }

    private Component message(ActionContext context, String key) {
        return miniMessage.deserialize(interpolate(context.getString(key), context));
    }

    private ProxyCommandPublisher requireProxyCommands() {
        if (proxyCommands == null) {
            throw new IllegalStateException("Proxy actions require redis.enabled=true");
        }
        return proxyCommands;
    }

    private static int integerArgument(Object value) {
        if (value == null) throw new IllegalArgumentException("Missing integer argument");
        return Integer.parseInt(String.valueOf(value));
    }

    private static String interpolate(String value, ActionContext context) {
        return value.replace("{event_id}", context.event().id())
                .replace("{execution_id}", context.executionId().toString())
                .replace("{server}", context.server());
    }
}
