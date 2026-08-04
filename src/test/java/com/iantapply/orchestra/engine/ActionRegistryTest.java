package com.iantapply.orchestra.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ActionRegistryTest {
    @Test
    void registrationsAreCaseInsensitiveAndUnique() {
        ActionRegistry registry = new ActionRegistry();
        var action = (com.iantapply.orchestra.api.OrchestraAction) context -> CompletableFuture.completedFuture(null);
        var condition =
                (com.iantapply.orchestra.api.OrchestraCondition) context -> CompletableFuture.completedFuture(true);

        registry.registerAction(" Broadcast ", action);
        registry.registerCondition("READY", condition);

        assertSame(action, registry.action("broadcast"));
        assertSame(condition, registry.condition(" ready "));
        assertThrows(IllegalStateException.class, () -> registry.registerAction("BROADCAST", action));
        assertThrows(IllegalStateException.class, () -> registry.registerCondition("ready", condition));
    }

    @Test
    void rejectsUnknownAndNullExtensions() {
        ActionRegistry registry = new ActionRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.action("missing"));
        assertThrows(IllegalArgumentException.class, () -> registry.condition("missing"));
        assertThrows(NullPointerException.class, () -> registry.registerAction("null", null));
        assertNotNull(registry);
    }
}
