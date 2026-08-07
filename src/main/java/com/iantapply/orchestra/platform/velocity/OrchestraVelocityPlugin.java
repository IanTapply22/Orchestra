package com.iantapply.orchestra.platform.velocity;

import com.google.inject.Inject;
import com.iantapply.orchestra.adapter.redis.RedisTransport;
import com.iantapply.orchestra.velocity.ProxyFacade;
import com.iantapply.orchestra.velocity.VelocityAgent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;

/** Velocity bootstrap for the proxy-side Orchestra agent. */
@Plugin(id = "orchestra", name = "Orchestra", version = BuildInfo.VERSION, authors = "Gucci Fox")
public final class OrchestraVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private RedisTransport transport;
    private VelocityAgent agent;

    /**
     * Creates the Velocity entry point.
     *
     * @param proxy active Velocity proxy
     * @param logger plugin logger
     */
    @Inject
    public OrchestraVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /**
     * Starts Redis transport and the proxy command agent.
     *
     * @param ignored Velocity initialization event
     */
    @Subscribe
    public void onInitialize(ProxyInitializeEvent ignored) {
        String redisUri = System.getProperty("orchestra.redis.uri", "redis://localhost:6379/0");
        String namespace = System.getProperty("orchestra.redis.namespace", "orchestra");
        String proxyId = System.getProperty("orchestra.proxy.id", "velocity-1");
        transport = new RedisTransport(URI.create(redisUri), namespace);
        agent = new VelocityAgent(proxyId, transport, new VelocityFacade());
        agent.start();
        logger.info("Orchestra Velocity agent {} started", proxyId);
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
