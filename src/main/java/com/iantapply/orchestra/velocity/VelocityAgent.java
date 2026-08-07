package com.iantapply.orchestra.velocity;

import com.iantapply.orchestra.port.NetworkTransport;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transport-facing Velocity agent; a proxy bootstrap supplies the small ProxyFacade implementation. */
public final class VelocityAgent implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(VelocityAgent.class.getName());
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
        timer.scheduleWithFixedDelay(this::safeHeartbeat, 0, 5, TimeUnit.SECONDS);
    }

    private void safeHeartbeat() {
        try {
            heartbeat();
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not publish Velocity heartbeat", failure);
        }
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
            default -> {}
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
