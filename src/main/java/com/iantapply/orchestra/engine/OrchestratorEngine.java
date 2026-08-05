package com.iantapply.orchestra.engine;

import com.iantapply.orchestra.api.ActionContext;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.EventLifecycleListener;
import com.iantapply.orchestra.api.EventStatus;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.domain.EventExecution;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.DistributedLock;
import com.iantapply.orchestra.port.ExecutionRepository;
import com.iantapply.orchestra.port.TargetResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Durable, at-least-once orchestration engine. Action implementations should use
 * {@link ActionContext#idempotencyKey()} when calling systems that support deduplication.
 */
public final class OrchestratorEngine implements AutoCloseable {
    private static final Duration LEASE_TIME = Duration.ofSeconds(30);
    private final DefinitionRepository definitions;
    private final ExecutionRepository executions;
    private final DistributedLock locks;
    private final EventStateMachine stateMachine = new EventStateMachine();
    private final StageExecutor stageExecutor;
    private final Clock clock;
    private final ScheduledExecutorService timer;
    private final ThreadPoolExecutor workers;
    private final CopyOnWriteArrayList<EventLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Creates an engine with bounded worker concurrency and queue capacity.
     *
     * @param definitions event definition store
     * @param executions durable execution store
     * @param locks distributed execution lease provider
     * @param targets target resolver
     * @param registry action and condition registry
     * @param clock engine clock
     * @param workerCount number of concurrent execution workers
     * @param queueCapacity maximum queued execution tasks
     */
    public OrchestratorEngine(
            DefinitionRepository definitions,
            ExecutionRepository executions,
            DistributedLock locks,
            TargetResolver targets,
            ActionRegistry registry,
            Clock clock,
            int workerCount,
            int queueCapacity) {
        this.definitions = definitions;
        this.executions = executions;
        this.locks = locks;
        this.clock = clock;
        this.stageExecutor = new StageExecutor(registry, targets, clock, this::replace);
        this.timer = Executors.newSingleThreadScheduledExecutor(namedFactory("orchestra-timer-"));
        this.workers = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedFactory("orchestra-worker-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    /** Starts the due-execution polling loop once. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            timer.scheduleWithFixedDelay(this::safeTick, 0, 250, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Creates a durable execution for a known definition.
     *
     * @param definitionId event definition identifier
     * @param startAt requested start time
     * @param variables initial execution variables
     * @return new execution identifier
     */
    public UUID schedule(String definitionId, Instant startAt, Map<String, Object> variables) {
        definitions
                .find(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown event: " + definitionId));
        Instant now = clock.instant();
        EventExecution base = EventExecution.scheduled(UUID.randomUUID(), definitionId, now, startAt);
        EventExecution execution = base.withVariables(variables, now);
        executions.create(execution);
        return execution.id();
    }

    /**
     * Schedules an event immediately with no initial variables.
     *
     * @param definitionId event definition to schedule immediately
     * @return new execution identifier
     */
    public UUID startNow(String definitionId) {
        return schedule(definitionId, clock.instant(), Map.of());
    }

    /**
     * Registers a lifecycle listener.
     *
     * @param listener lifecycle listener retained until the engine closes
     */
    public void addListener(EventLifecycleListener listener) {
        listeners.add(listener);
    }

    /**
     * Pauses an execution.
     *
     * @param id execution identifier
     * @return whether the execution was found and atomically paused
     */
    public boolean pause(UUID id) {
        return update(id, old -> transition(old, EventStatus.PAUSED, old.stageIndex(), null));
    }

    /**
     * Resumes an execution.
     *
     * @param id execution identifier
     * @return whether the execution was found and atomically resumed
     */
    public boolean resume(UUID id) {
        return update(
                id,
                old -> transition(
                        old,
                        old.stageIndex() < 0 ? EventStatus.SCHEDULED : EventStatus.RUNNING,
                        old.stageIndex(),
                        clock.instant()));
    }

    /**
     * Cancels an execution.
     *
     * @param id execution identifier
     * @return whether the execution was found and atomically cancelled
     */
    public boolean cancel(UUID id) {
        return update(id, old -> transition(old, EventStatus.CANCELLED, old.stageIndex(), null));
    }

    /**
     * Reschedules a failed execution from its first stage.
     *
     * @param id execution identifier
     * @return whether the failed execution was atomically rescheduled
     */
    public boolean retry(UUID id) {
        return update(id, old -> transition(old, EventStatus.SCHEDULED, -1, clock.instant()));
    }

    /**
     * Atomically sets or removes an execution variable.
     *
     * @param id execution identifier
     * @param key variable name
     * @param value new value, or {@code null} to remove it
     * @return whether the update succeeded
     */
    public boolean setVariable(UUID id, String key, Object value) {
        return update(id, old -> {
            var variables = new java.util.HashMap<>(old.variables());
            if (value == null) {
                variables.remove(key);
            } else {
                variables.put(key, value);
            }
            return old.withVariables(variables, clock.instant());
        });
    }

    /** Immediately scans persisted state; called on startup to recover interrupted work. */
    public void recover() {
        for (EventExecution execution : executions.findActive(10_000)) {
            if (execution.status() == EventStatus.STARTING || execution.status() == EventStatus.RUNNING) {
                submit(execution);
            }
        }
    }

    private void safeTick() {
        if (!running.get()) {
            return;
        }
        try {
            for (EventExecution due : executions.findDue(clock.instant(), 256)) {
                submit(due);
            }
        } catch (Throwable ignored) {
            // A repository outage must not cancel ScheduledExecutor's future invocations.
        }
    }

    private void submit(EventExecution execution) {
        workers.execute(() -> process(execution.id()));
    }

    private void process(UUID id) {
        try (DistributedLock.Lease ignored =
                locks.tryAcquire("execution:" + id, LEASE_TIME).orElse(null)) {
            if (ignored == null) {
                return;
            }
            EventExecution current = executions.find(id).orElse(null);
            if (current == null
                    || current.status() == EventStatus.PAUSED
                    || current.status() == EventStatus.CANCELLED) {
                return;
            }
            EventDefinition definition =
                    definitions.find(current.definitionId()).orElseThrow();
            if (current.status() == EventStatus.SCHEDULED) {
                EventExecution starting = transition(current, EventStatus.STARTING, 0, clock.instant());
                if (!replace(current, starting)) {
                    return;
                }
                current = starting;
            }
            if (current.status() != EventStatus.STARTING && current.status() != EventStatus.RUNNING) {
                return;
            }

            int stageIndex = current.status() == EventStatus.RUNNING ? current.stageIndex() + 1 : current.stageIndex();
            if (stageIndex >= definition.stages().size()) {
                replace(current, transition(current, EventStatus.COMPLETED, current.stageIndex(), null));
                return;
            }
            StageDefinition stage = definition.stages().get(stageIndex);
            if (!stageExecutor.conditionsPass(current, definition, stage)) {
                fail(current, "Conditions not met for stage " + stage.id());
                return;
            }
            current = stageExecutor.executeActions(current, definition, stage);
            Instant due = clock.instant().plus(stage.duration());
            EventExecution advanced = new EventExecution(
                    current.id(),
                    current.definitionId(),
                    EventStatus.RUNNING,
                    stageIndex,
                    current.version() + 1,
                    current.createdAt(),
                    clock.instant(),
                    due,
                    clock.instant(),
                    current.variables(),
                    current.completedActions(),
                    null);
            replace(current, advanced);
        } catch (Throwable failure) {
            executions.find(id).ifPresent(current -> fail(current, rootMessage(failure)));
        }
    }

    private boolean update(UUID id, UnaryOperator<EventExecution> operation) {
        for (int attempt = 0; attempt < 8; attempt++) {
            EventExecution before = executions.find(id).orElse(null);
            if (before == null) {
                return false;
            }
            EventExecution after = operation.apply(before);
            if (replace(before, after)) {
                return true;
            }
        }
        return false;
    }

    private EventExecution transition(EventExecution before, EventStatus status, int stage, Instant due) {
        stateMachine.requireTransition(before.status(), status);
        return before.transition(status, stage, clock.instant(), due);
    }

    private boolean replace(EventExecution before, EventExecution after) {
        boolean replaced = executions.compareAndSet(before.version(), after);
        if (replaced && before.status() != after.status()) {
            for (EventLifecycleListener listener : listeners) {
                try {
                    listener.onTransition(before, after);
                } catch (RuntimeException ignored) {
                    // One listener must not prevent the remaining listeners from observing the transition.
                }
            }
        }
        return replaced;
    }

    private void fail(EventExecution current, String message) {
        if (current.status() == EventStatus.FAILED
                || current.status() == EventStatus.COMPLETED
                || current.status() == EventStatus.CANCELLED) {
            return;
        }
        stateMachine.requireTransition(current.status(), EventStatus.FAILED);
        replace(current, current.withFailure(message, clock.instant()));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static ThreadFactory namedFactory(String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + sequence++);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /** Stops polling and shuts down engine workers. */
    @Override
    public void close() {
        running.set(false);
        timer.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
