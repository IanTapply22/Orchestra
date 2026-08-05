package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.adapter.postgres.MigrationRunner;
import com.iantapply.orchestra.adapter.postgres.PostgresAuditRepository;
import com.iantapply.orchestra.adapter.postgres.PostgresExecutionRepository;
import com.iantapply.orchestra.adapter.postgres.PostgresSettings;
import com.iantapply.orchestra.adapter.redis.RedisDistributedLock;
import com.iantapply.orchestra.audit.AuditRepository;
import com.iantapply.orchestra.audit.InMemoryAuditRepository;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.platform.paper.action.PaperActionRegistrar;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.DistributedLock;
import com.iantapply.orchestra.port.ExecutionRepository;
import com.iantapply.orchestra.schedule.RecurringEventScheduler;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.security.Role;
import com.iantapply.orchestra.web.OrchestraHttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point. All platform wiring lives here; orchestration behavior does not. */
public final class OrchestraPlugin extends JavaPlugin {
    private OrchestratorEngine engine;
    private final List<AutoCloseable> resources = new ArrayList<>();

    /** Creates the Paper plugin entry point. */
    public OrchestraPlugin() {}

    /** Wires infrastructure, loads definitions, recovers work, and starts services. */
    @Override
    public void onEnable() {
        saveDefaultConfig();

        Clock clock = Clock.systemUTC();
        Infrastructure infrastructure = createInfrastructure();
        ActionRegistry registry = new ActionRegistry();
        JoinGate joinGate = new JoinGate();

        engine = createEngine(infrastructure, registry, clock);
        registerActions(registry, joinGate);
        Bukkit.getPluginManager().registerEvents(joinGate, this);

        int loadedDefinitions = new EventDefinitionDirectory(this).loadInto(infrastructure.definitions());
        MetricsRegistry metrics = configureMetrics(infrastructure.executions());

        engine.recover();
        engine.start();

        startRecurringScheduler(infrastructure, clock);
        startWebServer(metrics);

        getLogger().info("Folia mode: " + FoliaSupport.isFolia());
        getLogger().info("Loaded " + loadedDefinitions + " event definition(s)");
    }

    /** Stops the engine and closes owned resources in reverse creation order. */
    @Override
    public void onDisable() {
        if (engine != null) {
            engine.close();
        }

        ListIterator<AutoCloseable> iterator = resources.listIterator(resources.size());
        while (iterator.hasPrevious()) {
            try {
                iterator.previous().close();
            } catch (Exception failure) {
                getLogger().warning("Shutdown error: " + failure.getMessage());
            }
        }
    }

    /**
     * Exposes the active engine to other plugins.
     *
     * @return enabled orchestration engine
     * @throws IllegalStateException before plugin enablement
     */
    public OrchestratorEngine engine() {
        if (engine == null) {
            throw new IllegalStateException("Orchestra is not enabled");
        }
        return engine;
    }

    private Infrastructure createInfrastructure() {
        InMemoryStores memory = new InMemoryStores();
        ExecutionRepository executions = memory;
        AuditRepository audit = new InMemoryAuditRepository(10_000);

        if (getConfig().getBoolean("postgres.enabled")) {
            HikariDataSource dataSource = openPostgres();
            executions = new PostgresExecutionRepository(dataSource);
            audit = new PostgresAuditRepository(dataSource);
        }

        DistributedLock locks = getConfig().getBoolean("redis.enabled") ? createRedisLock() : memory;
        return new Infrastructure(memory, executions, locks, audit);
    }

    private HikariDataSource openPostgres() {
        PostgresSettings settings = new PostgresSettings(
                getConfig().getString("postgres.jdbc-url", ""),
                getConfig().getString("postgres.username", ""),
                getConfig().getString("postgres.password", ""),
                getConfig().getInt("postgres.maximum-pool-size", 8));
        HikariDataSource dataSource = settings.openDataSource();
        resources.add(dataSource);

        try {
            new MigrationRunner(dataSource).migrate();
            return dataSource;
        } catch (Exception failure) {
            throw new IllegalStateException("PostgreSQL migration failed", failure);
        }
    }

    private DistributedLock createRedisLock() {
        URI redisUri = URI.create(getConfig().getString("redis.uri", "redis://localhost:6379/0"));
        String namespace = getConfig().getString("redis.namespace", "orchestra");
        return new RedisDistributedLock(redisUri, namespace);
    }

    private OrchestratorEngine createEngine(Infrastructure infrastructure, ActionRegistry registry, Clock clock) {
        int defaultWorkers = Math.min(4, Runtime.getRuntime().availableProcessors());
        int workers = Math.max(1, getConfig().getInt("engine.workers", defaultWorkers));
        int queueCapacity = Math.max(16, getConfig().getInt("engine.queue-capacity", 256));
        ServerIdentity identity = ServerIdentity.from(getConfig());

        return new OrchestratorEngine(
                infrastructure.definitions(),
                infrastructure.executions(),
                infrastructure.locks(),
                new PaperTargetResolver(identity),
                registry,
                clock,
                workers,
                queueCapacity);
    }

    private void registerActions(ActionRegistry registry, JoinGate joinGate) {
        new PaperActionRegistrar(this, joinGate).registerInto(registry);
    }

    private MetricsRegistry configureMetrics(ExecutionRepository executions) {
        MetricsRegistry metrics = new MetricsRegistry();
        metrics.gauge(
                "orchestra_active_executions",
                () -> executions.findActive(10_000).size());
        engine.addListener((before, after) ->
                getLogger().info("Event %s: %s -> %s".formatted(after.id(), before.status(), after.status())));
        engine.addListener((before, after) -> metrics.increment("orchestra_event_transitions_total"));
        return metrics;
    }

    private void startRecurringScheduler(Infrastructure infrastructure, Clock clock) {
        RecurringEventScheduler scheduler =
                new RecurringEventScheduler(infrastructure.definitions(), engine, infrastructure.locks(), clock);
        scheduler.start();
        resources.add(scheduler);
    }

    private void startWebServer(MetricsRegistry metrics) {
        if (!getConfig().getBoolean("web.enabled")) {
            return;
        }

        try {
            InetSocketAddress address = new InetSocketAddress(
                    getConfig().getString("web.bind", "127.0.0.1"), getConfig().getInt("web.port", 8787));
            OrchestraHttpServer web = new OrchestraHttpServer(address, metrics, apiTokens(), engine::startNow);
            web.start();
            resources.add(web);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not start web server", failure);
        }
    }

    private Map<String, Actor> apiTokens() {
        Map<String, Actor> result = new HashMap<>();
        var section = getConfig().getConfigurationSection("web.tokens");
        if (section == null) {
            return result;
        }

        section.getValues(false).forEach((token, roleName) -> {
            String actorId = "api:" + Integer.toHexString(token.hashCode());
            Role role = Role.valueOf(String.valueOf(roleName).toUpperCase(Locale.ROOT));
            result.put(token, new Actor(actorId, role));
        });
        return result;
    }

    /** Infrastructure services selected from configuration during startup. */
    private record Infrastructure(
            DefinitionRepository definitions,
            ExecutionRepository executions,
            DistributedLock locks,
            AuditRepository audit) {}
}
