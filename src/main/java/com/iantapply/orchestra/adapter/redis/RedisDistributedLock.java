package com.iantapply.orchestra.adapter.redis;

import com.iantapply.orchestra.port.DistributedLock;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis lease provider using atomic NX/PX acquisition and token-checked release. */
public final class RedisDistributedLock implements DistributedLock {
    private static final String RELEASE = "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
    private final URI uri;
    private final String namespace;

    /**
     * Creates a Redis-backed lease provider.
     *
     * @param uri Redis URI, optionally containing credentials and database number
     * @param namespace prefix used to isolate Orchestra keys
     */
    public RedisDistributedLock(URI uri, String namespace) {
        this.uri = uri;
        this.namespace = namespace.endsWith(":") ? namespace : namespace + ":";
    }

    @Override
    public Optional<Lease> tryAcquire(String key, Duration duration) {
        String namespaced = namespace + "lock:" + key;
        String token = UUID.randomUUID().toString();
        Object result = command("SET", namespaced, token, "NX", "PX", Long.toString(duration.toMillis()));
        return result == null ? Optional.empty() : Optional.of(new RedisLease(namespaced, token));
    }

    private Object command(String... values) {
        try (var connection = new RespConnection(uri, Duration.ofSeconds(3))) {
            return connection.command(RespConnection.strings(values));
        } catch (Exception failure) {
            throw new IllegalStateException("Redis command failed", failure);
        }
    }

    /** Lease retaining the random token required for owner-safe release. */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private final class RedisLease implements Lease {
        private final String key;
        private final String token;
        private final AtomicBoolean open = new AtomicBoolean(true);
        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                command("EVAL", RELEASE, "1", key, token);
            }
        }
    }
}
