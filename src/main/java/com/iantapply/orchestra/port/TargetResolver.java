package com.iantapply.orchestra.port;

import com.iantapply.orchestra.api.TargetSelector;

import java.util.Set;

/** Resolves declarative selectors against the current network topology. */
@FunctionalInterface
public interface TargetResolver {
    /**
     * Resolves a selector against current server state.
     *
     * @param selector declarative target selector
     * @return unique server names currently matching the selector
     */
    Set<String> resolve(TargetSelector selector);
}
