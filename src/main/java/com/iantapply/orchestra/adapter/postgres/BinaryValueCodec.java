package com.iantapply.orchestra.adapter.postgres;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** Compact, dependency-free binary codec for persisted execution maps and sets. */
@UtilityClass
class BinaryValueCodec {

    static byte[] encodeMap(Map<String, Object> values) {
        return write(output -> writeValue(output, values));
    }

    static byte[] encodeStrings(Set<String> values) {
        return write(output -> writeValue(output, values.stream().sorted().toList()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> decodeMap(byte[] bytes) {
        return (Map<String, Object>) read(bytes);
    }

    static Set<String> decodeStrings(byte[] bytes) {
        Object value = read(bytes);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Invalid string collection");
        Set<String> result = new HashSet<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private static byte[] write(IoConsumer<DataOutputStream> writer) {
        try (var bytes = new ByteArrayOutputStream();
                var output = new DataOutputStream(bytes)) {
            writer.accept(output);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static Object read(byte[] bytes) {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return readValue(input);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeValue(DataOutputStream output, Object value) throws IOException {
        switch (value) {
            case null -> output.writeByte(0);
            case String string -> {
                output.writeByte(1);
                output.writeUTF(string);
            }
            case Integer integer -> {
                output.writeByte(2);
                output.writeInt(integer);
            }
            case Long number -> {
                output.writeByte(3);
                output.writeLong(number);
            }
            case Double number -> {
                output.writeByte(4);
                output.writeDouble(number);
            }
            case Boolean bool -> {
                output.writeByte(5);
                output.writeBoolean(bool);
            }
            case Number number -> {
                output.writeByte(4);
                output.writeDouble(number.doubleValue());
            }
            case Map<?, ?> map -> {
                output.writeByte(6);
                output.writeInt(map.size());
                for (var entry : map.entrySet()) {
                    output.writeUTF(String.valueOf(entry.getKey()));
                    writeValue(output, entry.getValue());
                }
            }
            case Collection<?> collection -> {
                output.writeByte(7);
                output.writeInt(collection.size());
                for (Object item : collection) writeValue(output, item);
            }
            default -> {
                output.writeByte(1);
                output.writeUTF(String.valueOf(value));
            }
        }
    }

    private static Object readValue(DataInputStream input) throws IOException {
        return switch (input.readByte()) {
            case 0 -> null;
            case 1 -> input.readUTF();
            case 2 -> input.readInt();
            case 3 -> input.readLong();
            case 4 -> input.readDouble();
            case 5 -> input.readBoolean();
            case 6 -> {
                int size = input.readInt();
                Map<String, Object> map = new LinkedHashMap<>(size);
                for (int i = 0; i < size; i++) map.put(input.readUTF(), readValue(input));
                yield map;
            }
            case 7 -> {
                int size = input.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(readValue(input));
                yield list;
            }
            default -> throw new IOException("Unknown encoded value type");
        };
    }

    /** I/O callback used to centralize stream lifecycle and exception conversion. */
    @FunctionalInterface
    private interface IoConsumer<T> {
        void accept(T value) throws IOException;
    }
}
