package com.iantapply.orchestra.adapter.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minimal RESP2 socket connection used by the Redis adapters. */
final class RespConnection implements AutoCloseable {
    private static final byte[] CRLF = {'\r', '\n'};
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    RespConnection(URI uri, Duration timeout) throws IOException {
        if (!"redis".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("Only redis:// URIs are supported");
        socket = new Socket();
        socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort()), (int)
                timeout.toMillis());
        socket.setSoTimeout((int) timeout.toMillis());
        socket.setTcpNoDelay(true);
        input = new BufferedInputStream(socket.getInputStream(), 8_192);
        output = new BufferedOutputStream(socket.getOutputStream(), 8_192);
        authenticate(uri);
    }

    synchronized Object command(byte[]... arguments) throws IOException {
        write(arguments);
        return read();
    }

    synchronized void write(byte[]... arguments) throws IOException {
        output.write(('*' + Integer.toString(arguments.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] argument : arguments) {
            output.write(('$' + Integer.toString(argument.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(argument);
            output.write(CRLF);
        }
        output.flush();
    }

    synchronized Object read() throws IOException {
        int marker = input.read();
        if (marker < 0) throw new EOFException("Redis closed the connection");
        return switch (marker) {
            case '+' -> line();
            case '-' -> throw new IOException("Redis error: " + line());
            case ':' -> Long.parseLong(line());
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new IOException("Unknown RESP marker: " + (char) marker);
        };
    }

    private byte[] bulk() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException("Truncated Redis response");
        requireCrlf();
        return value;
    }

    private List<Object> array() throws IOException {
        int length = Integer.parseInt(line());
        List<Object> values = new ArrayList<>(Math.max(0, length));
        for (int i = 0; i < length; i++) values.add(read());
        return values;
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        int previous = -1;
        for (int value; (value = input.read()) >= 0; previous = value) {
            if (previous == '\r' && value == '\n') {
                byte[] line = bytes.toByteArray();
                return new String(line, 0, line.length - 1, StandardCharsets.UTF_8);
            }
            bytes.write(value);
        }
        throw new EOFException("Truncated Redis line");
    }

    private void requireCrlf() throws IOException {
        if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid RESP terminator");
    }

    private void authenticate(URI uri) throws IOException {
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            String[] credentials = uri.getUserInfo().split(":", 2);
            command(strings(
                    credentials.length == 1
                            ? new String[] {"AUTH", credentials[0]}
                            : new String[] {"AUTH", credentials[0], credentials[1]}));
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) command(strings("SELECT", path.substring(1)));
    }

    static byte[][] strings(String... values) {
        byte[][] result = new byte[values.length][];
        for (int i = 0; i < values.length; i++) result[i] = values[i].getBytes(StandardCharsets.UTF_8);
        return result;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
