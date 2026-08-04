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
        assertFalse(Role.OPERATOR.allows(Permission.APPROVE));
        assertTrue(Role.APPROVER.allows(Permission.APPROVE));
        for (Permission permission : Permission.values()) {
            assertTrue(Role.ADMINISTRATOR.allows(permission));
        }
    }

    @Test
    void actorsValidateIdsAndEnforcePermissions() {
        assertThrows(IllegalArgumentException.class, () -> new Actor(" ", Role.VIEWER));
        Actor actor = new Actor("staff", Role.OPERATOR);

        assertDoesNotThrow(() -> actor.require(Permission.OPERATE));
        assertThrows(SecurityException.class, () -> actor.require(Permission.APPROVE));
    }
}
