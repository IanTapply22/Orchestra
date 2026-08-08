package com.iantapply.orchestra.api;

import java.util.Map;
import java.util.Set;

/**
 * Selects target servers by identity, group, tags, or online status.
 *
 * @param servers explicit server names
 * @param groups configured server groups
 * @param tags labels that a server must match
 * @param allOnline whether every online server should be included
 */
public record TargetSelector(Set<String> servers, Set<String> groups, Map<String, String> tags, boolean allOnline) {
    /** Selector matching every online server. */
    public static final TargetSelector ALL_ONLINE = new TargetSelector(Set.of(), Set.of(), Map.of(), true);

    /** Snapshots selector values and rejects an empty selector. */
    public TargetSelector {
        servers = Set.copyOf(servers);
        groups = Set.copyOf(groups);
        tags = Map.copyOf(tags);
        if (!allOnline && servers.isEmpty() && groups.isEmpty() && tags.isEmpty()) {
            throw new IllegalArgumentException("A target selector cannot be empty");
        }
    }
}
