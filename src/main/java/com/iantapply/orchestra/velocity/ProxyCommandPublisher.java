package com.iantapply.orchestra.velocity;

import com.iantapply.orchestra.port.NetworkTransport;
import java.nio.charset.StandardCharsets;

/** Publishes validated commands understood by a configured Velocity agent. */
public final class ProxyCommandPublisher {
    private final NetworkTransport transport;

    /**
     * Creates a publisher over the shared network transport.
     *
     * @param transport transport used to reach proxy agents
     */
    public ProxyCommandPublisher(NetworkTransport transport) {
        this.transport = transport;
    }

    /**
     * Requests that a proxy move a player to a backend server.
     *
     * @param proxyId destination proxy
     * @param playerId player UUID
     * @param serverId destination backend
     */
    public void movePlayer(String proxyId, String playerId, String serverId) {
        publish(proxyId, "MOVE", playerId, serverId);
    }

    /**
     * Requests that a proxy update the advertised join state for a server group.
     *
     * @param proxyId destination proxy
     * @param group backend group
     * @param enabled desired join state
     */
    public void setGroupJoins(String proxyId, String group, boolean enabled) {
        publish(proxyId, "JOINS", group, Boolean.toString(enabled));
    }

    private void publish(String proxyId, String operation, String first, String second) {
        requireField("proxyId", proxyId);
        requireField("first argument", first);
        requireField("second argument", second);
        String payload = String.join("\t", operation, first, second);
        transport.publish("velocity:" + proxyId, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireField(String name, String value) {
        if (value == null || value.isBlank() || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
