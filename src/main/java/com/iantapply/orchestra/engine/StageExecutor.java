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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import lombok.RequiredArgsConstructor;

/** Executes one stage while the engine remains responsible for lifecycle transitions. */
@RequiredArgsConstructor
final class StageExecutor {
    private static final String SET_VARIABLE = "set_variable";

    private final ActionRegistry registry;
    private final TargetResolver targets;
    private final Clock clock;
    private final BiPredicate<EventExecution, EventExecution> persist;

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
        EventExecution current = initial;
        Set<String> servers = targets.resolve(event.targets());

        for (ActionSpec action : stage.actions()) {
            for (String server : servers) {
                String key = actionKey(current, stage, action, server);
                if (current.completedActions().contains(key)) continue;

                EventExecution next = SET_VARIABLE.equalsIgnoreCase(action.type())
                        ? applyVariable(current, action, key)
                        : runExternalAction(current, event, stage, action, server, key);

                if (!persist.test(current, next)) {
                    throw new IllegalStateException("Execution changed while action was running");
                }
                current = next;
            }
        }
        return current;
    }

    private EventExecution runExternalAction(
            EventExecution current,
            EventDefinition event,
            StageDefinition stage,
            ActionSpec action,
            String server,
            String key)
            throws Exception {
        ActionContext context =
                new ActionContext(current.id(), event, stage, action, server, clock.instant(), current.variables());
        executeWithRetry(context, stage.timeout());
        return current.withCompletedAction(key, clock.instant());
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

    private void executeWithRetry(ActionContext context, Duration timeout) throws Exception {
        RetryPolicy policy = context.action().retryPolicy();
        List<Throwable> failures = new ArrayList<>();

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            Duration delay = policy.delayBefore(attempt);
            if (!delay.isZero()) Thread.sleep(delay);
            try {
                registry.action(context.action().type())
                        .execute(context)
                        .toCompletableFuture()
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException | TimeoutException failure) {
                failures.add(failure);
            }
        }

        Exception exhausted = new Exception("Action failed after " + policy.maxAttempts() + " attempts: "
                + context.action().id());
        failures.forEach(exhausted::addSuppressed);
        throw exhausted;
    }

    private static String actionKey(EventExecution execution, StageDefinition stage, ActionSpec action, String server) {
        return execution.id() + ":" + stage.id() + ":" + action.id() + ":" + server;
    }
}
