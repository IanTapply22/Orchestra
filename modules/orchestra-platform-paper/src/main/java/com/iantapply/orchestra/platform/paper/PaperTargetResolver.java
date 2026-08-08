package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.api.TargetSelector;
import com.iantapply.orchestra.port.TargetResolver;
import java.util.Collections;
import java.util.Set;

/** Resolves selectors against the identity of the current Paper server. */
public final class PaperTargetResolver implements TargetResolver {
    private final ServerIdentity server;

    /**
     * Creates a local target resolver.
     *
     * @param server identity of the local backend
     */
    public PaperTargetResolver(ServerIdentity server) {
        this.server = server;
    }

    @Override
    public Set<String> resolve(TargetSelector selector) {
        boolean matches = selector.allOnline()
                || selector.servers().contains(server.id())
                || !Collections.disjoint(selector.groups(), server.groups())
                || tagsMatch(selector);
        return matches ? Set.of(server.id()) : Set.of();
    }

    private boolean tagsMatch(TargetSelector selector) {
        return !selector.tags().isEmpty()
                && server.tags().entrySet().containsAll(selector.tags().entrySet());
    }
}
