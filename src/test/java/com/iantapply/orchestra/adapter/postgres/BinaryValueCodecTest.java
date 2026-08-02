package com.iantapply.orchestra.adapter.postgres;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BinaryValueCodecTest {
    @Test void roundTripsNestedValues() {
        Map<String, Object> values = Map.of("multiplier", 2, "active", true, "nested", Map.of("name", "test"));
        assertEquals(values, BinaryValueCodec.decodeMap(BinaryValueCodec.encodeMap(values)));
        assertEquals(Set.of("a", "b"), BinaryValueCodec.decodeStrings(BinaryValueCodec.encodeStrings(Set.of("b", "a"))));
    }
}
