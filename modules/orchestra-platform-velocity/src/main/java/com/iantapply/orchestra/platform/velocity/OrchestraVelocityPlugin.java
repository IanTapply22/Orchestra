package com.iantapply.orchestra.platform.velocity;

import com.google.inject.Inject;
import com.iantapply.orchestra.adapter.redis.RedisTransport;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.velocity.ProxyFacade;
import com.iantapply.orchestra.velocity.VelocityAgent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;

/** Velocity bootstrap for the proxy-side Orchestra agent. */
@Plugin(id = "orchestra", name = "Orchestra", version = BuildInfo.VERSION, authors = "Gucci Fox")
public final class OrchestraVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final MetricsRegistry metrics = new MetricsRegistry();
    private RedisTransport transport;
    private VelocityAgent agent;

    /**
     * Creates the Velocity entry point.
     *
     * @param proxy active Velocity proxy
     * @param logger plugin logger
     * @param dataDirectory plugin configuration directory
     */
    @Inject
    public OrchestraVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Starts Redis transport and the proxy command agent.
     *
     * @param ignored Velocity initialization event
     */
    @Subscribe
    public void onInitialize(ProxyInitializeEvent ignored) {
        try {
            VelocitySettings settings = VelocitySettings.load(dataDirectory);
            transport = new RedisTransport(settings.redisUri(), settings.redisNamespace(), metrics::increment);
            metrics.gauge("orchestra_velocity_online_players", proxy::getPlayerCount);
            metrics.gauge("orchestra_redis_connected", () -> transport.isReachable() ? 1 : 0);
            if (!transport.isReachable()) {
                throw new IllegalStateException("Redis is not reachable at " + redact(settings.redisUri()));
            }
            agent = new VelocityAgent(settings.proxyId(), transport, new VelocityFacade());
            agent.start();
            logger.info("Orchestra Velocity agent {} started", settings.proxyId());
        } catch (Exception failure) {
            onShutdown(null);
            logger.error("Orchestra could not start: {}. Check {}", failure.getMessage(), dataDirectory, failure);
            throw new IllegalStateException("Orchestra Velocity startup failed", failure);
        }
    }

    /**
     * Stops the proxy agent and Redis transport.
     *
     * @param ignored Velocity shutdown event
     */
    @Subscribe
    public void onShutdown(ProxyShutdownEvent ignored) {
        if (agent != null) agent.close();
        if (transport != null) transport.close();
    }

    /** Returns proxy-side operational metrics for integrations. */
    public MetricsRegistry metrics() {
        return metrics;
    }

    private static String redact(java.net.URI uri) {
        String authority = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        return uri.getScheme() + "://" + authority + (uri.getPath() == null ? "" : uri.getPath());
    }

    /** Adapts Velocity's API to the transport-independent proxy facade. */
    private final class VelocityFacade implements ProxyFacade {
        @Override
        public int onlinePlayers() {
            return proxy.getPlayerCount();
        }

        @Override
        public void movePlayer(String playerId, String serverId) {
            try {
                var player = proxy.getPlayer(UUID.fromString(playerId));
                var server = proxy.getServer(serverId);
                if (player.isPresent() && server.isPresent())
                    player.get().createConnectionRequest(server.get()).fireAndForget();
            } catch (IllegalArgumentException invalid) {
                logger.warn("Rejected invalid Orchestra move request", invalid);
            }
        }

        @Override
        public void setGroupJoins(String group, boolean enabled) {
            logger.info("Join state for group {} changed to {}; backend agents enforce the gate", group, enabled);
        }
    }
}
