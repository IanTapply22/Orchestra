package com.iantapply.orchestra.velocity;

import com.iantapply.orchestra.port.NetworkTransport;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transport-facing Velocity agent; a proxy bootstrap supplies the small ProxyFacade implementation. */
public final class VelocityAgent implements AutoCloseable {
    /** Minimal proxy operations required by the transport-facing agent. */
    public interface ProxyFacade {
        /**
         * Counts connected players.
         *
         * @return current proxy-wide online player count
         */
        int onlinePlayers();

        /**
         * Requests that a player connect to a backend server.
         *
         * @param playerId player UUID
         * @param serverId destination backend name
         */
        void movePlayer(String playerId, String serverId);

        /**
         * Publishes or applies the requested join state for a server group.
         *
         * @param group server group
         * @param enabled whether the group should accept joins
         */
        void setGroupJoins(String group, boolean enabled);
    }

    private final String proxyId;
    private final NetworkTransport transport;
    private final ProxyFacade proxy;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("orchestra-velocity", 0).factory());
    private final AtomicBoolean open = new AtomicBoolean();
    private NetworkTransport.Subscription commands;

    /**
     * Creates a proxy agent.
     *
     * @param proxyId unique proxy identity
     * @param transport cross-server transport
     * @param proxy proxy operations facade
     */
    public VelocityAgent(String proxyId, NetworkTransport transport, ProxyFacade proxy) {
        this.proxyId = proxyId;
        this.transport = transport;
        this.proxy = proxy;
    }

    /** Starts command subscription and periodic proxy heartbeats once. */
    public void start() {
        if (!open.compareAndSet(false, true)) {
            return;
        }

        commands = transport.subscribe("velocity:" + proxyId, this::handle);
        timer.scheduleWithFixedDelay(this::heartbeat, 0, 5, TimeUnit.SECONDS);
    }

    private void heartbeat() {
        String value = proxyId + "\t" + proxy.onlinePlayers() + "\t" + System.currentTimeMillis();
        transport.publish("velocity:heartbeats", value.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(byte[] payload) {
        String[] command = new String(payload, StandardCharsets.UTF_8).split("\\t", 4);
        if (command.length == 0) {
            return;
        }

        switch (command[0]) {
            case "MOVE" -> movePlayer(command);
            case "JOINS" -> updateJoinState(command);
            default -> { }
        }
    }

    private void movePlayer(String[] command) {
        if (command.length >= 3) {
            proxy.movePlayer(command[1], command[2]);
        }
    }

    private void updateJoinState(String[] command) {
        if (command.length >= 3) {
            proxy.setGroupJoins(command[1], Boolean.parseBoolean(command[2]));
        }
    }

    /** Stops heartbeats and closes the command subscription. */
    @Override
    public void close() {
        open.set(false);
        timer.shutdownNow();
        if (commands != null) {
            commands.close();
        }
    }
}
