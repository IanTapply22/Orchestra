package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.adapter.memory.InMemoryStores;
import com.iantapply.orchestra.adapter.postgres.MigrationRunner;
import com.iantapply.orchestra.adapter.postgres.PostgresAuditRepository;
import com.iantapply.orchestra.adapter.postgres.PostgresExecutionRepository;
import com.iantapply.orchestra.adapter.postgres.PostgresSettings;
import com.iantapply.orchestra.adapter.redis.RedisDistributedLock;
import com.iantapply.orchestra.adapter.redis.RedisTransport;
import com.iantapply.orchestra.audit.AuditRepository;
import com.iantapply.orchestra.audit.InMemoryAuditRepository;
import com.iantapply.orchestra.engine.ActionRegistry;
import com.iantapply.orchestra.engine.EngineOptions;
import com.iantapply.orchestra.engine.OrchestratorEngine;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.platform.paper.action.PaperActionRegistrar;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.DistributedLock;
import com.iantapply.orchestra.port.ExecutionRepository;
import com.iantapply.orchestra.schedule.RecurringEventScheduler;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.security.Role;
import com.iantapply.orchestra.velocity.ProxyCommandPublisher;
import com.iantapply.orchestra.web.OrchestraHttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
        MetricsRegistry metrics = new MetricsRegistry();
        Infrastructure infrastructure = createInfrastructure(metrics);
        ActionRegistry registry = new ActionRegistry();
        JoinGate joinGate = new JoinGate();

        engine = createEngine(infrastructure, registry, clock, metrics);
        registerActions(registry, joinGate, infrastructure.proxyCommands());
        Bukkit.getPluginManager().registerEvents(joinGate, this);

        EventDefinitionDirectory definitionDirectory = new EventDefinitionDirectory(this);
        int loadedDefinitions = definitionDirectory.loadInto(infrastructure.definitions());
        configureMetrics(metrics, infrastructure.executions());

        engine.recover();
        engine.start();

        startRecurringScheduler(infrastructure, clock, metrics);
        startWebServer(metrics, infrastructure.audit(), clock);

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

    private Infrastructure createInfrastructure(MetricsRegistry metrics) {
        InMemoryStores memory = new InMemoryStores();
        ExecutionRepository executions = memory;
        AuditRepository audit = new InMemoryAuditRepository(10_000);

        if (getConfig().getBoolean("postgres.enabled")) {
            HikariDataSource dataSource = openPostgres();
            executions = new PostgresExecutionRepository(dataSource);
            audit = new PostgresAuditRepository(dataSource);
        }

        DistributedLock locks = memory;
        ProxyCommandPublisher proxyCommands = null;
        if (getConfig().getBoolean("redis.enabled")) {
            URI redisUri = redisUri();
            String namespace = redisNamespace();
            locks = new RedisDistributedLock(redisUri, namespace);
            RedisTransport transport = new RedisTransport(redisUri, namespace, metrics::increment);
            resources.add(transport);
            proxyCommands = new ProxyCommandPublisher(transport);
        }
        return new Infrastructure(memory, executions, locks, audit, proxyCommands);
    }

    private HikariDataSource openPostgres() {
        PostgresSettings settings = new PostgresSettings(
                getConfig().getString("postgres.jdbc-url", ""),
                getConfig().getString("postgres.username", ""),
                secret("postgres.password", "postgres.password-environment-variable", "postgres.password-file"),
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

    private URI redisUri() {
        String environmentName = getConfig().getString("redis.uri-environment-variable", "ORCHESTRA_REDIS_URI");
        String environmentValue = System.getenv(environmentName);
        String fileValue = readOptionalSecretFile("redis.uri-file");
        String configured = getConfig().getString("redis.uri", "redis://localhost:6379/0");
        String value = environmentValue != null && !environmentValue.isBlank()
                ? environmentValue
                : fileValue != null ? fileValue : configured;
        return URI.create(value);
    }

    private String redisNamespace() {
        return getConfig().getString("redis.namespace", "orchestra");
    }

    private OrchestratorEngine createEngine(
            Infrastructure infrastructure, ActionRegistry registry, Clock clock, MetricsRegistry metrics) {
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
                new EngineOptions(
                        workers,
                        queueCapacity,
                        Duration.ofMillis(Math.max(50, getConfig().getLong("engine.poll-interval-ms", 250))),
                        Math.max(1, getConfig().getInt("engine.poll-batch-size", 256)),
                        Duration.ofSeconds(Math.max(10, getConfig().getLong("engine.lease-seconds", 600))),
                        Duration.ofSeconds(Math.max(1, getConfig().getLong("engine.shutdown-seconds", 10)))),
                metrics::increment);
    }

    private void registerActions(ActionRegistry registry, JoinGate joinGate, ProxyCommandPublisher proxyCommands) {
        new PaperActionRegistrar(this, joinGate, proxyCommands).registerInto(registry);
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

    private void startRecurringScheduler(Infrastructure infrastructure, Clock clock, MetricsRegistry metrics) {
        RecurringEventScheduler scheduler = new RecurringEventScheduler(
                infrastructure.definitions(), engine, infrastructure.locks(), clock, metrics::increment);
        scheduler.start();
        resources.add(scheduler);
    }

    private void startWebServer(MetricsRegistry metrics, AuditRepository audit, Clock clock) {
        if (!getConfig().getBoolean("web.enabled")) {
            return;
        }

        try {
            Map<String, Actor> tokens = apiTokens();
            if (tokens.isEmpty()) {
                throw new IllegalStateException("web.enabled requires at least one bearer token");
            }
            InetSocketAddress address = new InetSocketAddress(
                    getConfig().getString("web.bind", "127.0.0.1"), getConfig().getInt("web.port", 8787));
            OrchestraHttpServer web = new OrchestraHttpServer(address, metrics, tokens, engine::startNow, audit, clock);
            web.start();
            resources.add(web);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not start web server", failure);
        }
    }

    private Map<String, Actor> apiTokens() {
        Map<String, Actor> result = new HashMap<>();
        var section = getConfig().getConfigurationSection("web.tokens");
        if (section != null) {
            section.getValues(false).forEach((token, roleName) -> addToken(result, token, roleName));
        }

        String environmentName = getConfig().getString("web.token-environment-variable", "ORCHESTRA_WEB_TOKEN");
        String environmentToken = System.getenv(environmentName);
        if (environmentToken != null && !environmentToken.isBlank()) {
            addToken(result, environmentToken, Role.ADMINISTRATOR.name());
        }
        String fileToken = readOptionalSecretFile("web.token-file");
        if (fileToken != null) addToken(result, fileToken, Role.ADMINISTRATOR.name());
        return result;
    }

    private static void addToken(Map<String, Actor> destination, String token, Object roleName) {
        if (token.length() < 24 || token.startsWith("replace-with")) {
            throw new IllegalArgumentException("Web bearer tokens must contain at least 24 characters");
        }
        String actorId = "api:" + UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8));
        Role role = Role.valueOf(String.valueOf(roleName).toUpperCase(Locale.ROOT));
        destination.put(token, new Actor(actorId, role));
    }

    private String secret(String configPath, String environmentPath, String filePath) {
        String environmentName = Objects.requireNonNullElse(getConfig().getString(environmentPath), "");
        String environmentValue = environmentName.isBlank() ? null : System.getenv(environmentName);
        String fileValue = readOptionalSecretFile(filePath);
        String configuredValue = Objects.requireNonNullElse(getConfig().getString(configPath), "");
        String value = environmentValue != null && !environmentValue.isBlank()
                ? environmentValue
                : fileValue != null ? fileValue : configuredValue;
        if (value.isBlank() || value.equals("change-me")) {
            throw new IllegalArgumentException("Missing secure value for " + configPath);
        }
        return value;
    }

    private String readOptionalSecretFile(String configPath) {
        String configuredPath = Objects.requireNonNullElse(getConfig().getString(configPath), "");
        if (configuredPath.isBlank()) return null;
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Secret file is not a regular file: " + path);
            }
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (value.isBlank()) throw new IllegalArgumentException("Secret file is empty: " + path);
            return value;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Could not read secret file: " + path, failure);
        }
    }

    /** Infrastructure services selected from configuration during startup. */
    private record Infrastructure(
            DefinitionRepository definitions,
            ExecutionRepository executions,
            DistributedLock locks,
            AuditRepository audit,
            ProxyCommandPublisher proxyCommands) {}
}
