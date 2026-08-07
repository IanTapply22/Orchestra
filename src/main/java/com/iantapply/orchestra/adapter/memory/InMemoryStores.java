package com.iantapply.orchestra.adapter.memory;

import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.EventStatus;
import com.iantapply.orchestra.domain.EventExecution;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.DistributedLock;
import com.iantapply.orchestra.port.ExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe in-memory implementation of the core stores and lease provider.
 * Intended for tests, development, and single-process installations.
 */
public final class InMemoryStores implements DefinitionRepository, ExecutionRepository, DistributedLock {
    private final ConcurrentMap<String, EventDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, EventExecution> executions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalLease> locks = new ConcurrentHashMap<>();

    /** Creates empty in-memory definition, execution, and lease stores. */
    public InMemoryStores() {}

    @Override
    public void save(EventDefinition definition) {
        definitions.put(definition.id(), definition);
    }

    @Override
    public Optional<EventDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @Override
    public Collection<EventDefinition> findAll() {
        return List.copyOf(definitions.values());
    }

    @Override
    public void create(EventExecution execution) {
        if (executions.putIfAbsent(execution.id(), execution) != null) {
            throw new IllegalStateException("Duplicate execution: " + execution.id());
        }
    }

    @Override
    public Optional<EventExecution> find(UUID id) {
        return Optional.ofNullable(executions.get(id));
    }

    @Override
    public Collection<EventExecution> findDue(Instant now, int limit) {
        return executions.values().stream()
                .filter(e -> e.dueAt() != null && !e.dueAt().isAfter(now))
                .filter(this::isRunnable)
                .sorted(Comparator.comparing(EventExecution::dueAt))
                .limit(limit)
                .toList();
    }

    @Override
    public Collection<EventExecution> findActive(int limit) {
        return executions.values().stream().filter(this::isActive).limit(limit).toList();
    }

    @Override
    public boolean compareAndSet(long expectedVersion, EventExecution replacement) {
        AtomicBoolean changed = new AtomicBoolean();
        executions.computeIfPresent(replacement.id(), (ignored, current) -> {
            if (current.version() != expectedVersion) return current;
            changed.set(true);
            return replacement;
        });
        return changed.get();
    }

    @Override
    public Optional<Lease> tryAcquire(String key, Duration duration) {
        Instant now = Instant.now();
        LocalLease offered = new LocalLease(key, now.plus(duration));
        LocalLease result = locks.compute(
                key, (ignored, current) -> current == null || current.expiresAt.isBefore(now) ? offered : current);
        return result == offered ? Optional.of(offered) : Optional.empty();
    }

    private boolean isRunnable(EventExecution execution) {
        return execution.status() == EventStatus.SCHEDULED
                || execution.status() == EventStatus.STARTING
                || execution.status() == EventStatus.RUNNING;
    }

    private boolean isActive(EventExecution execution) {
        return isRunnable(execution) || execution.status() == EventStatus.PAUSED;
    }

    /** Process-local lease removed only by the instance that acquired it. */
    private final class LocalLease implements Lease {
        private final String key;
        private volatile Instant expiresAt;

        private LocalLease(String key, Instant expiresAt) {
            this.key = key;
            this.expiresAt = expiresAt;
        }

        @Override
        public boolean renew(Duration duration) {
            if (locks.get(key) != this) return false;
            expiresAt = Instant.now().plus(duration);
            return true;
        }

        @Override
        public void close() {
            locks.remove(key, this);
        }
    }
}
