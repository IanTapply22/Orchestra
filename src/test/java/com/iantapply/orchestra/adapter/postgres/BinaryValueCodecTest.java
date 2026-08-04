package com.iantapply.orchestra.adapter.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BinaryValueCodecTest {
    @Test
    void roundTripsNestedValues() {
        Map<String, Object> values = Map.of(
                "multiplier",
                2,
                "long",
                3L,
                "ratio",
                1.5,
                "active",
                true,
                "list",
                java.util.List.of("one", 2),
                "nested",
                Map.of("name", "test"));
        assertEquals(values, BinaryValueCodec.decodeMap(BinaryValueCodec.encodeMap(values)));
        assertEquals(
                Set.of("a", "b"), BinaryValueCodec.decodeStrings(BinaryValueCodec.encodeStrings(Set.of("b", "a"))));
    }

    @Test
    void rejectsInvalidPayloads() {
        assertThrows(IllegalArgumentException.class, () -> BinaryValueCodec.decodeStrings(new byte[] {1, 0, 1, 'x'}));
        assertThrows(java.io.UncheckedIOException.class, () -> BinaryValueCodec.decodeMap(new byte[] {99}));
    }
}
