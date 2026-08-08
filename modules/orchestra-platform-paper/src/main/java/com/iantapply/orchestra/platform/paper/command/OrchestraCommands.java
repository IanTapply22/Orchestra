package com.iantapply.orchestra.platform.paper.command;

import com.iantapply.orchestra.administration.DefinitionValidationReport;
import com.iantapply.orchestra.administration.OrchestraAdministrationService;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.domain.EventExecution;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Small Brigadier command tree that adapts Paper senders to platform-neutral administration operations. */
public final class OrchestraCommands {
    /** Permission required for every Orchestra administration command. */
    public static final String PERMISSION = "orchestra.admin";

    private static final int EXECUTION_LIMIT = 100;
    private final OrchestraAdministrationService administration;
    private final Supplier<List<String>> diagnostics;

    /**
     * Creates the command adapter.
     *
     * @param administration platform-neutral administration service
     * @param diagnostics safe platform diagnostic lines
     */
    public OrchestraCommands(OrchestraAdministrationService administration, Supplier<List<String>> diagnostics) {
        this.administration = administration;
        this.diagnostics = diagnostics;
    }

    /**
     * Builds the complete {@code /orchestra} command tree.
     *
     * @return lifecycle-registerable Brigadier root node
     */
    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("orchestra")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> usage(context.getSource().getSender()))
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("events").executes(this::events))
                .then(Commands.literal("validate").executes(this::validate))
                .then(Commands.literal("reload").executes(this::reload))
                .then(Commands.literal("start")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    administration.events().stream()
                                            .map(EventDefinition::id)
                                            .filter(id -> id.startsWith(builder.getRemainingLowerCase()))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(this::start)))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("execution", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    administration.executions(EXECUTION_LIMIT).stream()
                                            .map(execution -> execution.id().toString())
                                            .filter(id -> id.startsWith(builder.getRemainingLowerCase()))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(this::cancel)))
                .then(Commands.literal("executions").executes(this::executions))
                .then(Commands.literal("diagnostics").executes(this::diagnostics))
                .build();
    }

    private int status(CommandContext<CommandSourceStack> context) {
        success(context, administration.status().summary());
        return Command.SINGLE_SUCCESS;
    }

    private int events(CommandContext<CommandSourceStack> context) {
        List<EventDefinition> events = administration.events();
        success(context, events.size() + " registered event(s)");
        events.forEach(event -> detail(
                context,
                "%s — %s (%d stage%s)"
                        .formatted(
                                event.id(),
                                event.displayName(),
                                event.stages().size(),
                                event.stages().size() == 1 ? "" : "s")));
        return Command.SINGLE_SUCCESS;
    }

    private int validate(CommandContext<CommandSourceStack> context) {
        return report(context, administration.validate(), "Validation succeeded");
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        return report(context, administration.reload(), "Reload succeeded");
    }

    private int start(CommandContext<CommandSourceStack> context) {
        return safely(context, () -> {
            String event = StringArgumentType.getString(context, "event");
            UUID execution = administration.start(event);
            success(context, "Started " + event + " as " + execution);
        });
    }

    private int cancel(CommandContext<CommandSourceStack> context) {
        try {
            UUID execution = UUID.fromString(StringArgumentType.getString(context, "execution"));
            if (administration.cancel(execution)) {
                success(context, "Cancelled execution " + execution);
                return Command.SINGLE_SUCCESS;
            } else {
                failure(context, "Execution was not found or could not be cancelled: " + execution);
                return 0;
            }
        } catch (RuntimeException failure) {
            return commandFailure(context, failure);
        }
    }

    private int executions(CommandContext<CommandSourceStack> context) {
        Collection<EventExecution> executions = administration.executions(EXECUTION_LIMIT);
        success(context, executions.size() + " active execution(s)");
        executions.stream()
                .sorted(java.util.Comparator.comparing(EventExecution::createdAt)
                        .reversed())
                .forEach(execution -> detail(
                        context,
                        "%s — %s — %s"
                                .formatted(
                                        execution.id(),
                                        execution.definitionId(),
                                        execution.status().name().toLowerCase(Locale.ROOT))));
        return Command.SINGLE_SUCCESS;
    }

    private int diagnostics(CommandContext<CommandSourceStack> context) {
        success(context, "Orchestra diagnostics");
        diagnostics.get().forEach(line -> detail(context, line));
        detail(context, administration.status().summary());
        return Command.SINGLE_SUCCESS;
    }

    private static int usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "Usage: /orchestra <status|events|validate|reload|start|cancel|executions|diagnostics>",
                NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    private static int report(
            CommandContext<CommandSourceStack> context, DefinitionValidationReport report, String successMessage) {
        if (report.valid()) {
            success(context, successMessage + ": " + report.summary());
            return Command.SINGLE_SUCCESS;
        }
        failure(context, report.summary());
        return 0;
    }

    private static int safely(CommandContext<CommandSourceStack> context, CommandOperation operation) {
        try {
            operation.run();
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException failure) {
            return commandFailure(context, failure);
        }
    }

    private static int commandFailure(CommandContext<CommandSourceStack> context, RuntimeException exception) {
        String message = exception.getMessage();
        failure(
                context,
                message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        return 0;
    }

    private static void success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void detail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void failure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().getSender().sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @FunctionalInterface
    private interface CommandOperation {
        void run();
    }
}
