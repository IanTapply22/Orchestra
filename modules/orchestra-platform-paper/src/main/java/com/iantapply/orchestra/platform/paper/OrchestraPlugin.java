package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.administration.DefaultOrchestraService;
import com.iantapply.orchestra.administration.OrchestraAdministrationService;
import com.iantapply.orchestra.api.OrchestraService;
import com.iantapply.orchestra.audit.AuditRepository;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.platform.paper.action.PaperActionRegistrar;
import com.iantapply.orchestra.platform.paper.command.OrchestraCommands;
import com.iantapply.orchestra.port.ExecutionRepository;
import com.iantapply.orchestra.schedule.RecurringEventScheduler;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.web.OrchestraHttpServer;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Thin Paper entry point that owns lifecycle and composes platform-neutral services. */
public final class OrchestraPlugin extends JavaPlugin {
    private OrchestratorEngine engine;
    private DefaultOrchestraService service;
    private final List<AutoCloseable> resources = new ArrayList<>();

    /** Creates the Paper plugin entry point. */
    public OrchestraPlugin() {}

    /** Wires infrastructure, loads definitions, recovers work, and starts services. */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        PaperSettings settings = new PaperSettings(getConfig(), getDataFolder().toPath());
        Clock clock = Clock.systemUTC();
        MetricsRegistry metrics = new MetricsRegistry();
        PaperInfrastructure infrastructure = new PaperInfrastructureFactory(settings, metrics, resources::add).create();
        ActionRegistry actions = new ActionRegistry();
        JoinGate joinGate = new JoinGate();

        engine = new OrchestratorEngine(
                infrastructure.definitions(),
                infrastructure.executions(),
                infrastructure.locks(),
                new PaperTargetResolver(settings.serverIdentity()),
                actions,
                clock,
                settings.engineOptions(),
                metrics::increment);
        service =
                new DefaultOrchestraService(engine, actions, infrastructure.definitions(), infrastructure.executions());

        new PaperActionRegistrar(this, joinGate, infrastructure.proxyCommands()).registerInto(actions);
        Bukkit.getPluginManager().registerEvents(joinGate, this);
        EventDefinitionDirectory definitionDirectory = new EventDefinitionDirectory(this);
        definitionDirectory.loadInto(infrastructure.definitions());
        configureMetrics(metrics, infrastructure.executions());

        engine.recover();
        engine.start();
        Bukkit.getServicesManager().register(OrchestraService.class, service, this, ServicePriority.Normal);
        registerCommands(
                new OrchestraAdministrationService(
                        service, infrastructure.definitions(), definitionDirectory::validate),
                settings);
        startRecurringScheduler(infrastructure, clock, metrics);
        startWebServer(settings, metrics, infrastructure.audit(), clock);

        getLogger().info("Folia mode: " + FoliaSupport.isFolia());
        getLogger().info("Ready: " + service.status().summary());
    }

    /** Stops the engine and closes owned resources in reverse creation order. */
    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (engine != null) engine.close();

        ListIterator<AutoCloseable> iterator = resources.listIterator(resources.size());
        while (iterator.hasPrevious()) {
            try {
                iterator.previous().close();
            } catch (Exception failure) {
                getLogger().warning("Shutdown error: " + failure.getMessage());
            }
        }
        resources.clear();
        service = null;
        engine = null;
    }

    private void configureMetrics(MetricsRegistry metrics, ExecutionRepository executions) {
        metrics.gauge(
                "orchestra_active_executions",
                () -> executions.findActive(10_000).size());
        metrics.gauge("orchestra_worker_active", engine::activeWorkerCount);
        metrics.gauge("orchestra_worker_queue_size", engine::queuedTaskCount);
        engine.addListener((before, after) ->
                getLogger().info("Event %s: %s -> %s".formatted(after.id(), before.status(), after.status())));
        engine.addListener((before, after) -> metrics.increment("orchestra_event_transitions_total"));
    }

    private void registerCommands(OrchestraAdministrationService administration, PaperSettings settings) {
        OrchestraCommands commands =
                new OrchestraCommands(administration, () -> PaperDiagnostics.create(this, settings));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(commands.create(), "Administer Orchestra events"));
    }

    private void startRecurringScheduler(PaperInfrastructure infrastructure, Clock clock, MetricsRegistry metrics) {
        RecurringEventScheduler scheduler = new RecurringEventScheduler(
                infrastructure.definitions(), engine, infrastructure.locks(), clock, metrics::increment);
        scheduler.start();
        resources.add(scheduler);
    }

    private void startWebServer(PaperSettings settings, MetricsRegistry metrics, AuditRepository audit, Clock clock) {
        if (!settings.webEnabled()) return;
        try {
            Map<String, Actor> tokens = settings.apiTokens();
            if (tokens.isEmpty()) {
                throw new IllegalStateException("web.enabled requires at least one bearer token");
            }
            OrchestraHttpServer web =
                    new OrchestraHttpServer(settings.webAddress(), metrics, tokens, engine::startNow, audit, clock);
            web.start();
            resources.add(web);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not start web server", failure);
        }
    }
}
