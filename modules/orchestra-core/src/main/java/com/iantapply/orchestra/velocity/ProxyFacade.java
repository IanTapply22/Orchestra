package com.iantapply.orchestra.velocity;

/** Platform-neutral proxy operations used by the Velocity transport agent. */
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
