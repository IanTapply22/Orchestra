package com.iantapply.orchestra.engine;

import com.iantapply.orchestra.api.ActionContext;
import com.iantapply.orchestra.api.ActionSpec;
import com.iantapply.orchestra.api.ConditionContext;
import com.iantapply.orchestra.api.ConditionSpec;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.RetryPolicy;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.domain.EventExecution;
import com.iantapply.orchestra.port.TargetResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Executes one stage while the engine remains responsible for lifecycle transitions. */
final class StageExecutor {
    private static final String SET_VARIABLE = "set_variable";

    private final ActionRegistry registry;
    private final TargetResolver targets;
    private final Clock clock;
    private final BiPredicate<EventExecution, EventExecution> persist;
    private final Consumer<String> failureCounter;

    StageExecutor(
            ActionRegistry registry,
            TargetResolver targets,
            Clock clock,
            BiPredicate<EventExecution, EventExecution> persist) {
        this(registry, targets, clock, persist, ignored -> {});
    }

    StageExecutor(
            ActionRegistry registry,
            TargetResolver targets,
            Clock clock,
            BiPredicate<EventExecution, EventExecution> persist,
            Consumer<String> failureCounter) {
        this.registry = registry;
        this.targets = targets;
        this.clock = clock;
        this.persist = persist;
        this.failureCounter = failureCounter;
    }

    boolean conditionsPass(EventExecution execution, EventDefinition event, StageDefinition stage) throws Exception {
        for (ConditionSpec condition : stage.conditions()) {
            ConditionContext context = new ConditionContext(
                    execution.id(), event, stage, condition, clock.instant(), execution.variables());
            boolean passed = registry.condition(condition.type())
                    .test(context)
                    .toCompletableFuture()
                    .get(stage.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!passed) return false;
        }
        return true;
    }

    EventExecution executeActions(EventExecution initial, EventDefinition event, StageDefinition stage)
            throws Exception {
        try {
            return executeActionsAsync(initial, event, stage)
                    .toCompletableFuture()
                    .get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    CompletionStage<EventExecution> executeActionsAsync(
            EventExecution initial, EventDefinition event, StageDefinition stage) {
        CompletionStage<EventExecution> pipeline = CompletableFuture.completedFuture(initial);
        Set<String> servers = targets.resolve(event.targets());
        for (ActionSpec action : stage.actions()) {
            for (String server : servers) {
                pipeline = pipeline.thenCompose(current -> executeAction(current, event, stage, action, server));
            }
        }
        return pipeline;
    }

    private CompletionStage<EventExecution> executeAction(
            EventExecution current, EventDefinition event, StageDefinition stage, ActionSpec action, String server) {
        String key = actionKey(current, stage, action, server);
        if (current.completedActions().contains(key)) {
            return CompletableFuture.completedFuture(current);
        }
        if (SET_VARIABLE.equalsIgnoreCase(action.type())) {
            return persist(current, applyVariable(current, action, key));
        }

        ActionContext context =
                new ActionContext(current.id(), event, stage, action, server, clock.instant(), current.variables());
        return executeWithRetry(context, stage.timeout(), 1, new ArrayList<>())
                .thenCompose(ignored -> persist(current, current.withCompletedAction(key, clock.instant())));
    }

    private CompletionStage<EventExecution> persist(EventExecution before, EventExecution after) {
        if (!persist.test(before, after)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Execution changed while action was running"));
        }
        return CompletableFuture.completedFuture(after);
    }

    private EventExecution applyVariable(EventExecution current, ActionSpec action, String actionKey) {
        String key = String.valueOf(action.arguments().get("key"));
        if (key.isBlank() || "null".equals(key)) {
            throw new IllegalArgumentException("set_variable requires key");
        }

        var variables = new HashMap<>(current.variables());
        Object value = action.arguments().get("value");
        if (value == null) variables.remove(key);
        else variables.put(key, value);

        var completed = new HashSet<>(current.completedActions());
        completed.add(actionKey);
        return new EventExecution(
                current.id(),
                current.definitionId(),
                current.status(),
                current.stageIndex(),
                current.version() + 1,
                current.createdAt(),
                clock.instant(),
                current.dueAt(),
                current.stageStartedAt(),
                variables,
                completed,
                current.failure());
    }

    private CompletionStage<Void> executeWithRetry(
            ActionContext context, Duration timeout, int attempt, List<Throwable> failures) {
        RetryPolicy policy = context.action().retryPolicy();
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable runAttempt = () -> {
            try {
                registry.action(context.action().type())
                        .execute(context)
                        .toCompletableFuture()
                        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                result.complete(null);
                            } else {
                                retryOrFail(context, timeout, attempt, failures, unwrap(failure), result);
                            }
                        });
            } catch (Throwable failure) {
                retryOrFail(context, timeout, attempt, failures, failure, result);
            }
        };

        Duration delay = policy.delayBefore(attempt);
        if (delay.isZero()) runAttempt.run();
        else
            CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(runAttempt);
        return result;
    }

    private void retryOrFail(
            ActionContext context,
            Duration timeout,
            int attempt,
            List<Throwable> failures,
            Throwable failure,
            CompletableFuture<Void> result) {
        RetryPolicy policy = context.action().retryPolicy();
        failures.add(failure);
        if (attempt < policy.maxAttempts()) {
            executeWithRetry(context, timeout, attempt + 1, failures)
                    .whenComplete((ignored, retryFailure) -> complete(result, retryFailure));
            return;
        }
        failureCounter.accept("orchestra_action_failures_total");
        Exception exhausted = new Exception("Action failed after " + policy.maxAttempts() + " attempts: "
                + context.action().id());
        failures.forEach(exhausted::addSuppressed);
        result.completeExceptionally(exhausted);
    }

    private static void complete(CompletableFuture<Void> destination, Throwable failure) {
        if (failure == null) destination.complete(null);
        else destination.completeExceptionally(unwrap(failure));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String actionKey(EventExecution execution, StageDefinition stage, ActionSpec action, String server) {
        return execution.id() + ":" + stage.id() + ":" + action.id() + ":" + server;
    }
}
