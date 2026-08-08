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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Durable, at-least-once orchestration engine. Action implementations should use
 * {@link ActionContext#idempotencyKey()} when calling systems that support deduplication.
 */
public final class OrchestratorEngine implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(OrchestratorEngine.class.getName());
    private final DefinitionRepository definitions;
    private final ExecutionRepository executions;
    private final DistributedLock locks;
    private final EventStateMachine stateMachine = new EventStateMachine();
    private final StageExecutor stageExecutor;
    private final Clock clock;
    private final EngineOptions options;
    private final ScheduledExecutorService timer;
    private final ScheduledExecutorService leaseTimer;
    private final ThreadPoolExecutor workers;
    private final Consumer<String> failureCounter;
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
        this(
                definitions,
                executions,
                locks,
                targets,
                registry,
                clock,
                EngineOptions.defaults(workerCount, queueCapacity),
                ignored -> {});
    }

    /**
     * Creates an engine with explicit operational timing and capacity options.
     *
     * @param definitions event definition store
     * @param executions durable execution store
     * @param locks distributed execution lease provider
     * @param targets target resolver
     * @param registry action and condition registry
     * @param clock engine clock
     * @param options operational timing and capacity options
     */
    public OrchestratorEngine(
            DefinitionRepository definitions,
            ExecutionRepository executions,
            DistributedLock locks,
            TargetResolver targets,
            ActionRegistry registry,
            Clock clock,
            EngineOptions options) {
        this(definitions, executions, locks, targets, registry, clock, options, ignored -> {});
    }

    /**
     * Creates an engine whose operational failure paths increment named counters.
     *
     * @param definitions event definition store
     * @param executions durable execution store
     * @param locks distributed execution lease provider
     * @param targets target resolver
     * @param registry action and condition registry
     * @param clock engine clock
     * @param options operational timing and capacity options
     * @param failureCounter consumer of metric names for failures and rejected work
     */
    public OrchestratorEngine(
            DefinitionRepository definitions,
            ExecutionRepository executions,
            DistributedLock locks,
            TargetResolver targets,
            ActionRegistry registry,
            Clock clock,
            EngineOptions options,
            Consumer<String> failureCounter) {
        this.definitions = definitions;
        this.executions = executions;
        this.locks = locks;
        this.clock = clock;
        this.options = options;
        this.failureCounter = failureCounter;
        this.stageExecutor = new StageExecutor(registry, targets, clock, this::replace, failureCounter);
        this.timer = Executors.newSingleThreadScheduledExecutor(namedFactory("orchestra-timer-"));
        this.leaseTimer = Executors.newSingleThreadScheduledExecutor(namedFactory("orchestra-lease-"));
        this.workers = new ThreadPoolExecutor(
                options.workerCount(),
                options.workerCount(),
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(options.queueCapacity()),
                namedFactory("orchestra-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    /** Starts the due-execution polling loop once. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            timer.scheduleWithFixedDelay(
                    this::safeTick, 0, options.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
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
            for (EventExecution due : executions.findDue(clock.instant(), options.pollBatchSize())) {
                submit(due);
            }
        } catch (Throwable failure) {
            failureCounter.accept("orchestra_poll_failures_total");
            LOGGER.log(System.Logger.Level.WARNING, "Execution polling failed", failure);
        }
    }

    private void submit(EventExecution execution) {
        try {
            workers.execute(() -> process(execution.id()));
        } catch (RejectedExecutionException saturated) {
            failureCounter.accept("orchestra_rejected_tasks_total");
            LOGGER.log(System.Logger.Level.WARNING, "Execution queue is full; work will be retried by polling");
        }
    }

    private void process(UUID id) {
        DistributedLock.Lease lease =
                locks.tryAcquire("execution:" + id, options.leaseDuration()).orElse(null);
        if (lease == null) return;
        long renewalMillis = Math.max(1, options.leaseDuration().toMillis() / 3);
        ScheduledFuture<?> renewal = leaseTimer.scheduleAtFixedRate(
                () -> renewLease(id, lease), renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
        boolean asynchronous = false;
        try {
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
            var actionExecution = stageExecutor.executeActionsAsync(current, definition, stage);
            asynchronous = true;
            actionExecution.whenComplete((completed, failure) -> {
                try {
                    if (failure != null) {
                        failureCounter.accept("orchestra_execution_failures_total");
                        executions.find(id).ifPresent(latest -> fail(latest, rootMessage(failure)));
                        return;
                    }
                    Instant due = clock.instant().plus(stage.duration());
                    EventExecution advanced = new EventExecution(
                            completed.id(),
                            completed.definitionId(),
                            EventStatus.RUNNING,
                            stageIndex,
                            completed.version() + 1,
                            completed.createdAt(),
                            clock.instant(),
                            due,
                            clock.instant(),
                            completed.variables(),
                            completed.completedActions(),
                            null);
                    replace(completed, advanced);
                } finally {
                    closeLease(renewal, lease);
                }
            });
        } catch (Throwable failure) {
            failureCounter.accept("orchestra_execution_failures_total");
            executions.find(id).ifPresent(current -> fail(current, rootMessage(failure)));
        } finally {
            if (!asynchronous) closeLease(renewal, lease);
        }
    }

    private static void closeLease(ScheduledFuture<?> renewal, DistributedLock.Lease lease) {
        renewal.cancel(false);
        lease.close();
    }

    private void renewLease(UUID id, DistributedLock.Lease lease) {
        try {
            if (!lease.renew(options.leaseDuration())) {
                failureCounter.accept("orchestra_lease_renewal_failures_total");
                LOGGER.log(System.Logger.Level.WARNING, "Lost execution lease for " + id);
            }
        } catch (RuntimeException failure) {
            failureCounter.accept("orchestra_lease_renewal_failures_total");
            LOGGER.log(System.Logger.Level.WARNING, "Could not renew execution lease for " + id, failure);
        }
    }

    /**
     * Returns the number of execution tasks currently waiting for a worker.
     *
     * @return queued task count
     */
    public int queuedTaskCount() {
        return workers.getQueue().size();
    }

    /**
     * Returns the number of workers currently executing a task.
     *
     * @return active worker count
     */
    public int activeWorkerCount() {
        return workers.getActiveCount();
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
                } catch (RuntimeException failure) {
                    failureCounter.accept("orchestra_listener_failures_total");
                    LOGGER.log(System.Logger.Level.WARNING, "Lifecycle listener failed", failure);
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
        leaseTimer.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(options.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
