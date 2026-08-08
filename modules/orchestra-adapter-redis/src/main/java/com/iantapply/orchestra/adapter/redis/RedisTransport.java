package com.iantapply.orchestra.adapter.redis;

import com.iantapply.orchestra.port.NetworkTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Redis Pub/Sub transport with daemon subscription threads and reconnect handling. */
public final class RedisTransport implements NetworkTransport {
    private static final System.Logger LOGGER = System.getLogger(RedisTransport.class.getName());
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private final URI uri;
    private final String namespace;
    private final Set<RedisSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong nextReconnectWarning = new AtomicLong();
    private final Consumer<String> failureCounter;

    /**
     * Creates a Redis Pub/Sub transport.
     *
     * @param uri Redis URI, optionally containing credentials and database number
     * @param namespace prefix applied to logical channels
     */
    public RedisTransport(URI uri, String namespace) {
        this(uri, namespace, ignored -> {});
    }

    /**
     * Creates a Redis transport with an operational failure counter sink.
     *
     * @param uri Redis URI
     * @param namespace channel namespace
     * @param failureCounter counter-name sink
     */
    public RedisTransport(URI uri, String namespace, Consumer<String> failureCounter) {
        this.uri = uri;
        this.namespace = namespace.endsWith(":") ? namespace : namespace + ":";
        this.failureCounter = failureCounter;
    }

    @Override
    public void publish(String channel, byte[] payload) {
        requireOpen();
        try (var connection = new RespConnection(uri, Duration.ofSeconds(3))) {
            connection.command(
                    "PUBLISH".getBytes(StandardCharsets.UTF_8), key(channel).getBytes(StandardCharsets.UTF_8), payload);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not publish Redis message", failure);
        }
    }

    @Override
    public Subscription subscribe(String channel, Consumer<byte[]> listener) {
        requireOpen();
        RedisSubscription subscription = new RedisSubscription(key(channel), listener);
        subscriptions.add(subscription);
        subscription.start();
        return subscription;
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            subscriptions.forEach(RedisSubscription::close);
        }
    }

    private String key(String channel) {
        return namespace + channel;
    }

    private void requireOpen() {
        if (!open.get()) {
            throw new IllegalStateException("Transport is closed");
        }
    }

    /** One reconnecting Redis subscription and its daemon reader thread. */
    private final class RedisSubscription implements Subscription, Runnable {
        private final String channel;
        private final Consumer<byte[]> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile RespConnection connection;

        private RedisSubscription(String channel, Consumer<byte[]> listener) {
            this.channel = channel;
            this.listener = listener;
        }

        private void start() {
            Thread.ofPlatform().daemon().name("orchestra-redis-sub").start(this);
        }

        @Override
        public void run() {
            if (!active.get() || !open.get()) return;
            try (var current = new RespConnection(uri, Duration.ofSeconds(30))) {
                connection = current;
                current.write(RespConnection.strings("SUBSCRIBE", channel));
                while (active.get()) dispatch(current.read());
            } catch (Exception failure) {
                failureCounter.accept("orchestra_redis_reconnects_total");
                logReconnectFailure(failure);
                scheduleReconnect();
            }
        }

        private void logReconnectFailure(Exception failure) {
            long now = System.nanoTime();
            long next = nextReconnectWarning.get();
            if (now >= next && nextReconnectWarning.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
                LOGGER.log(System.Logger.Level.WARNING, "Redis subscription disconnected; reconnecting", failure);
            }
        }

        private void scheduleReconnect() {
            if (!active.get() || !open.get()) return;
            CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(this::start);
        }

        private void dispatch(Object response) {
            if (!(response instanceof List<?> values) || values.size() != 3) {
                return;
            }
            String type = new String((byte[]) values.get(0), StandardCharsets.UTF_8);
            if ("message".equals(type) && values.get(2) instanceof byte[] payload) {
                listener.accept(payload);
            }
        }

        @Override
        public void close() {
            active.set(false);
            subscriptions.remove(this);
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
                // The subscription is already shutting down.
            }
        }
    }
}
