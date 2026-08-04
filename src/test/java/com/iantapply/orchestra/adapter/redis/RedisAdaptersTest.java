package com.iantapply.orchestra.adapter.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RedisAdaptersTest {
    @Test
    void connectionAuthenticatesSelectsDatabaseAndParsesResponses() throws Exception {
        try (MiniRedis redis = new MiniRedis()) {
            URI uri = URI.create("redis://user:password@127.0.0.1:" + redis.port() + "/2");
            try (RespConnection connection = new RespConnection(uri, Duration.ofSeconds(2))) {
                Object echo = connection.command(RespConnection.strings("ECHO", "hello"));
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), (byte[]) echo);
                assertEquals(42L, connection.command(RespConnection.strings("NUMBER")));
                assertEquals(2, ((List<?>) connection.command(RespConnection.strings("ARRAY"))).size());
                assertThrows(IOException.class, () -> connection.command(RespConnection.strings("ERROR")));
            }

            assertEquals(List.of("AUTH", "user", "password"), redis.commands().get(0));
            assertEquals(List.of("SELECT", "2"), redis.commands().get(1));
        }
    }

    @Test
    void distributedLockUsesNamespacedTokenCheckedRelease() throws Exception {
        try (MiniRedis redis = new MiniRedis()) {
            RedisDistributedLock locks =
                    new RedisDistributedLock(URI.create("redis://127.0.0.1:" + redis.port()), "orchestra");

            var lease = locks.tryAcquire("execution:one", Duration.ofSeconds(3)).orElseThrow();
            lease.close();
            lease.close();

            awaitCommands(redis, 2);
            List<String> set = redis.commands().get(0);
            List<String> release = redis.commands().get(1);
            assertEquals(List.of("SET", "orchestra:lock:execution:one"), set.subList(0, 2));
            assertEquals("NX", set.get(3));
            assertEquals("PX", set.get(4));
            assertEquals("3000", set.get(5));
            assertEquals("EVAL", release.getFirst());
            assertEquals("orchestra:lock:execution:one", release.get(3));
            assertEquals(set.get(2), release.get(4));
            assertEquals(2, redis.commands().size());
        }
    }

    @Test
    void transportPublishesBinaryPayloadAndRejectsWorkAfterClose() throws Exception {
        try (MiniRedis redis = new MiniRedis()) {
            RedisTransport transport = new RedisTransport(URI.create("redis://127.0.0.1:" + redis.port()), "network:");
            byte[] payload = {0, 1, 2, 3};

            transport.publish("events", payload);
            awaitCommands(redis, 1);
            assertEquals("PUBLISH", redis.commands().getFirst().getFirst());
            assertEquals("network:events", redis.commands().getFirst().get(1));
            assertArrayEquals(payload, redis.rawCommands().getFirst().get(2));

            transport.close();
            assertThrows(IllegalStateException.class, () -> transport.publish("events", payload));
            assertThrows(IllegalStateException.class, () -> transport.subscribe("events", ignored -> {}));
        }
    }

    @Test
    void transportSubscriptionDispatchesBinaryMessages() throws Exception {
        try (MiniRedis redis = new MiniRedis()) {
            RedisTransport transport = new RedisTransport(URI.create("redis://127.0.0.1:" + redis.port()), "network");
            CountDownLatch received = new CountDownLatch(1);
            var payload = new java.util.concurrent.atomic.AtomicReference<byte[]>();

            var subscription = transport.subscribe("events", value -> {
                payload.set(value);
                received.countDown();
            });

            assertTrue(received.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertArrayEquals(new byte[] {5, 6, 7}, payload.get());
            subscription.close();
            transport.close();
        }
    }

    private static void awaitCommands(MiniRedis redis, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (redis.commands().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(redis.commands().size() >= count);
    }

    private static final class MiniRedis implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService clients = Executors.newCachedThreadPool();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final List<List<byte[]>> rawCommands = new CopyOnWriteArrayList<>();

        private MiniRedis() throws IOException {
            server = new ServerSocket(0);
            Thread.ofPlatform().daemon().name("mini-redis-accept").start(this::accept);
        }

        int port() {
            return server.getLocalPort();
        }

        List<List<byte[]>> rawCommands() {
            return rawCommands;
        }

        List<List<String>> commands() {
            return rawCommands.stream()
                    .map(command -> command.stream()
                            .map(value -> new String(value, StandardCharsets.UTF_8))
                            .toList())
                    .toList();
        }

        private void accept() {
            while (open.get()) {
                try {
                    Socket socket = server.accept();
                    clients.execute(() -> handle(socket));
                } catch (IOException failure) {
                    if (open.get()) throw new RuntimeException(failure);
                }
            }
        }

        private void handle(Socket socket) {
            try (socket;
                    var input = new BufferedInputStream(socket.getInputStream());
                    var output = new BufferedOutputStream(socket.getOutputStream())) {
                while (open.get()) {
                    List<byte[]> command = readCommand(input);
                    rawCommands.add(command);
                    respond(output, new String(command.getFirst(), StandardCharsets.UTF_8), command);
                    output.flush();
                }
            } catch (EOFException ignored) {
                // Client closed normally.
            } catch (IOException failure) {
                if (open.get()) throw new RuntimeException(failure);
            }
        }

        private static List<byte[]> readCommand(BufferedInputStream input) throws IOException {
            if (input.read() != '*') throw new EOFException();
            int count = Integer.parseInt(readLine(input));
            List<byte[]> command = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (input.read() != '$') throw new IOException("Expected bulk argument");
                int length = Integer.parseInt(readLine(input));
                byte[] value = input.readNBytes(length);
                if (value.length != length) throw new EOFException();
                if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid terminator");
                command.add(value);
            }
            return command;
        }

        private static String readLine(BufferedInputStream input) throws IOException {
            var bytes = new java.io.ByteArrayOutputStream();
            int previous = -1;
            for (int value; (value = input.read()) >= 0; previous = value) {
                if (previous == '\r' && value == '\n') {
                    byte[] line = bytes.toByteArray();
                    return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
                }
                bytes.write(value);
            }
            throw new EOFException();
        }

        private static void respond(BufferedOutputStream output, String name, List<byte[]> command) throws IOException {
            switch (name) {
                case "ECHO" -> bulk(output, command.get(1));
                case "NUMBER", "PUBLISH", "EVAL" -> output.write(":42\r\n".getBytes(StandardCharsets.US_ASCII));
                case "ARRAY" -> output.write("*2\r\n+OK\r\n:7\r\n".getBytes(StandardCharsets.US_ASCII));
                case "SUBSCRIBE" -> {
                    byte[] channel = command.get(1);
                    output.write("*3\r\n$9\r\nsubscribe\r\n".getBytes(StandardCharsets.US_ASCII));
                    bulk(output, channel);
                    output.write(":1\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.write("*3\r\n$7\r\nmessage\r\n".getBytes(StandardCharsets.US_ASCII));
                    bulk(output, channel);
                    bulk(output, new byte[] {5, 6, 7});
                }
                case "ERROR" -> output.write("-ERR test failure\r\n".getBytes(StandardCharsets.US_ASCII));
                default -> output.write("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }

        private static void bulk(BufferedOutputStream output, byte[] value) throws IOException {
            output.write(("$" + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(value);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws Exception {
            open.set(false);
            server.close();
            clients.shutdownNow();
            clients.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
