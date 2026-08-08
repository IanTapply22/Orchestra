package com.iantapply.orchestra.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityTest {
    @Test
    void rolesGrantOnlyTheirDeclaredPermissions() {
        assertTrue(Role.VIEWER.allows(Permission.VIEW));
        assertFalse(Role.VIEWER.allows(Permission.OPERATE));
        assertTrue(Role.OPERATOR.allows(Permission.OPERATE));
        for (Permission permission : Permission.values()) {
            assertTrue(Role.ADMINISTRATOR.allows(permission));
        }
    }

    @Test
    void actorsValidateIdsAndEnforcePermissions() {
        assertThrows(IllegalArgumentException.class, () -> new Actor(" ", Role.VIEWER));
        Actor actor = new Actor("staff", Role.VIEWER);

        assertDoesNotThrow(() -> actor.require(Permission.VIEW));
        assertThrows(SecurityException.class, () -> actor.require(Permission.OPERATE));
    }
}
