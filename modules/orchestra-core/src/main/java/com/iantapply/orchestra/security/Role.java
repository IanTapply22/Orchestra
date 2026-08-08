package com.iantapply.orchestra.security;

import java.util.EnumSet;
import java.util.Set;

/** Built-in role-to-permission mappings used by the HTTP API. */
public enum Role {
    /** Read-only access. */
    VIEWER(EnumSet.of(Permission.VIEW)),
    /** Read and execution-operation access. */
    OPERATOR(EnumSet.of(Permission.VIEW, Permission.OPERATE)),
    /** Every available permission. */
    ADMINISTRATOR(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    /**
     * Tests whether this role includes a permission.
     *
     * @param permission permission to test
     * @return whether this role grants the permission
     */
    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }
}
