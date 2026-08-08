package com.iantapply.orchestra.adapter.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BinaryValueCodecTest {
    @Test
    void roundTripsNestedValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("multiplier", 2);
        values.put("long", 3L);
        values.put("ratio", 1.5);
        values.put("float", 2.5F);
        values.put("active", true);
        values.put("missing", null);
        values.put("list", java.util.List.of("one", 2));
        values.put("nested", Map.of("name", "test"));
        Map<String, Object> decoded = BinaryValueCodec.decodeMap(BinaryValueCodec.encodeMap(values));
        assertEquals(2.5D, decoded.get("float"));
        decoded.remove("float");
        values.remove("float");
        assertEquals(values, decoded);
        assertEquals(
                Set.of("a", "b"), BinaryValueCodec.decodeStrings(BinaryValueCodec.encodeStrings(Set.of("b", "a"))));
    }

    @Test
    void rejectsInvalidPayloads() {
        assertThrows(IllegalArgumentException.class, () -> BinaryValueCodec.decodeStrings(new byte[] {1, 0, 1, 'x'}));
        assertThrows(java.io.UncheckedIOException.class, () -> BinaryValueCodec.decodeMap(new byte[] {99}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BinaryValueCodec.encodeMap(Map.of("oversized", "x".repeat(1024 * 1024 + 1))));
        assertThrows(IllegalArgumentException.class, () -> BinaryValueCodec.decodeMap(new byte[16 * 1024 * 1024 + 1]));
        assertThrows(java.io.UncheckedIOException.class, () -> BinaryValueCodec.decodeMap(header(99, new byte[0])));
        assertThrows(
                java.io.UncheckedIOException.class,
                () -> BinaryValueCodec.decodeMap(header(2, new byte[] {6, 0, 1, (byte) 0x86, (byte) 0xa1})));
        assertThrows(
                java.io.UncheckedIOException.class,
                () -> BinaryValueCodec.decodeMap(header(2, new byte[] {1, 0, 16, 0, 1})));
        byte[] valid = BinaryValueCodec.encodeMap(Map.of("ok", true));
        assertThrows(
                java.io.UncheckedIOException.class,
                () -> BinaryValueCodec.decodeMap(java.util.Arrays.copyOf(valid, valid.length + 1)));
    }

    @Test
    void readsLegacyVersionOnePayloads() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
            output.writeByte(6);
            output.writeInt(1);
            output.writeUTF("legacy");
            output.writeByte(1);
            output.writeUTF("value");
        }
        assertEquals(Map.of("legacy", "value"), BinaryValueCodec.decodeMap(bytes.toByteArray()));
    }

    private static byte[] header(int version, byte[] body) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(5 + body.length);
        buffer.putInt(0x4f524348).put((byte) version).put(body);
        return buffer.array();
    }
}
