package com.iantapply.orchestra.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.iantapply.orchestra.api.TargetSelector;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PaperTargetResolverTest {
    @Test
    void loadsIdentityAndMatchesEverySelectorKind() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("server.id", "survival-1");
        config.set("server.groups", java.util.List.of("survival", "events"));
        config.set("server.tags.region", "na-east");
        config.set("server.tags.game", "survival");
        ServerIdentity identity = ServerIdentity.from(config);
        PaperTargetResolver resolver = new PaperTargetResolver(identity);

        assertEquals("survival-1", identity.id());
        assertEquals(Set.of("survival", "events"), identity.groups());
        assertEquals(Set.of("survival-1"), resolver.resolve(TargetSelector.ALL_ONLINE));
        assertEquals(
                Set.of("survival-1"),
                resolver.resolve(new TargetSelector(Set.of("survival-1"), Set.of(), Map.of(), false)));
        assertEquals(
                Set.of("survival-1"),
                resolver.resolve(new TargetSelector(Set.of(), Set.of("survival"), Map.of(), false)));
        assertEquals(
                Set.of("survival-1"),
                resolver.resolve(new TargetSelector(Set.of(), Set.of(), Map.of("region", "na-east"), false)));
        assertEquals(
                Set.of(), resolver.resolve(new TargetSelector(Set.of(), Set.of(), Map.of("region", "eu-west"), false)));
    }

    @Test
    void identitySnapshotsCollections() {
        var groups = new java.util.HashSet<>(Set.of("one"));
        var tags = new java.util.HashMap<>(Map.of("region", "one"));
        ServerIdentity identity = new ServerIdentity("server", groups, tags);
        groups.add("two");
        tags.put("region", "two");

        assertEquals(Set.of("one"), identity.groups());
        assertEquals(Map.of("region", "one"), identity.tags());
    }
}
