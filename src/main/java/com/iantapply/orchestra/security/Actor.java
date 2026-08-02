package com.iantapply.orchestra.security;

/**
 * Authenticated principal and its assigned role.
 *
 * @param id stable principal identifier
 * @param role authorization role
 */
public record Actor(String id, Role role) {
    /** Validates that the actor has a usable identifier. */
    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Actor id is required");
        }
    }

    /**
     * Requires this actor to hold a permission.
     *
     * @param permission required permission
     * @throws SecurityException when the role does not allow it
     */
    public void require(Permission permission) {
        if (!role.allows(permission)) {
            throw new SecurityException(id + " lacks " + permission);
        }
    }
}
