package com.iantapply.orchestra.distribution;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DistributionContentsTest {
    @Test
    void containsBothPlatformDescriptorsAndDatabaseMigration() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        var paper = loader.getResourceAsStream("paper-plugin.yml");
        var velocity = loader.getResourceAsStream("velocity-plugin.json");
        assertNotNull(paper);
        assertNotNull(velocity);
        assertNotNull(loader.getResource("db/migration/V001__orchestra_core.sql"));
        try (paper;
                velocity) {
            assertTrue(new String(paper.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("com.iantapply.orchestra.platform.paper.OrchestraPlugin"));
            assertTrue(new String(velocity.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("com.iantapply.orchestra.platform.velocity.OrchestraVelocityPlugin"));
        }
    }
}
