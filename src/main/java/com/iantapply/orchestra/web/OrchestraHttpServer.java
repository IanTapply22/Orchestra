package com.iantapply.orchestra.web;

import com.iantapply.orchestra.audit.AuditEntry;
import com.iantapply.orchestra.audit.AuditRepository;
import com.iantapply.orchestra.audit.InMemoryAuditRepository;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.security.Permission;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small authenticated HTTP server exposing health and Prometheus metrics. */
public final class OrchestraHttpServer implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(OrchestraHttpServer.class.getName());
    private final HttpServer server;
    private final ExecutorService executor;
    private final MetricsRegistry metrics;
    private final Map<String, Actor> tokens;
    private final EventTrigger eventTrigger;
    private final AuditRepository audit;
    private final Clock clock;

    /**
     * Creates the HTTP listener without starting it.
     *
     * @param address bind address
     * @param metrics metrics registry exposed at {@code /metrics}
     * @param tokens bearer-token to actor mappings
     * @param eventTrigger callback that creates an immediate event execution
     * @throws IOException when the listening server cannot be created
     */
    public OrchestraHttpServer(
            InetSocketAddress address, MetricsRegistry metrics, Map<String, Actor> tokens, EventTrigger eventTrigger)
            throws IOException {
        this(address, metrics, tokens, eventTrigger, new InMemoryAuditRepository(1), Clock.systemUTC());
    }

    /**
     * Creates the HTTP listener with durable operation auditing.
     *
     * @param address bind address
     * @param metrics metrics registry exposed at {@code /metrics}
     * @param tokens bearer-token to actor mappings
     * @param eventTrigger callback that creates an immediate event execution
     * @param audit destination for successful operator actions
     * @param clock audit timestamp source
     * @throws IOException when the listening server cannot be created
     */
    public OrchestraHttpServer(
            InetSocketAddress address,
            MetricsRegistry metrics,
            Map<String, Actor> tokens,
            EventTrigger eventTrigger,
            AuditRepository audit,
            Clock clock)
            throws IOException {
        this.server = HttpServer.create(address, 64);
        this.metrics = metrics;
        this.tokens = Map.copyOf(tokens);
        this.eventTrigger = eventTrigger;
        this.audit = audit;
        this.clock = clock;

        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("orchestra-http-", 0).factory());
        server.setExecutor(executor);
        server.createContext("/health", exchange -> getOnly(exchange, () -> text(exchange, 200, "ok\n", "text/plain")));
        server.createContext(
                "/metrics",
                exchange -> getOnly(
                        exchange,
                        () -> authenticated(
                                exchange,
                                Permission.VIEW,
                                _ -> text(exchange, 200, metrics.prometheus(), "text/plain; version=0.0.4"))));
        server.createContext(
                "/events/",
                exchange -> authenticated(exchange, Permission.OPERATE, actor -> triggerEvent(exchange, actor)));
    }

    /** Starts accepting HTTP requests. */
    public void start() {
        server.start();
    }

    /** Stops accepting requests after a one-second grace period. */
    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }

    private void authenticated(HttpExchange exchange, Permission permission, Handler handler) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Actor actor = authorization != null && authorization.startsWith("Bearer ")
                ? authenticate(authorization.substring(7))
                : null;
        if (actor == null) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            text(exchange, 401, "unauthorized\n", "text/plain");
            return;
        }

        try {
            actor.require(permission);
            handler.handle(actor);
        } catch (SecurityException denied) {
            text(exchange, 403, "forbidden\n", "text/plain");
        }
    }

    private Actor authenticate(String supplied) {
        for (var entry : tokens.entrySet()) {
            byte[] configuredToken = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] suppliedToken = supplied.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(configuredToken, suppliedToken)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void triggerEvent(HttpExchange exchange, Actor actor) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            text(exchange, 405, "method not allowed\n", "text/plain");
            return;
        }

        String definitionId = executionDefinitionId(exchange.getRequestURI().getPath());
        if (definitionId == null) {
            text(exchange, 404, "not found\n", "text/plain");
            return;
        }

        try {
            UUID executionId = eventTrigger.start(definitionId);
            try {
                audit.append(new AuditEntry(
                        clock.instant(),
                        actor.id(),
                        "start_execution",
                        "event:" + definitionId,
                        "execution:" + executionId,
                        exchange.getRemoteAddress().getAddress().getHostAddress()));
            } catch (RuntimeException failure) {
                metrics.increment("orchestra_audit_failures_total");
                LOGGER.log(System.Logger.Level.WARNING, "Could not persist operation audit entry", failure);
            }
            String response =
                    "{\"execution_id\":\"%s\",\"definition_id\":\"%s\"}\n".formatted(executionId, definitionId);
            text(exchange, 202, response, "application/json");
        } catch (IllegalArgumentException unknownEvent) {
            text(exchange, 404, "unknown event\n", "text/plain");
        }
    }

    private static void getOnly(HttpExchange exchange, IoAction action) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            text(exchange, 405, "method not allowed\n", "text/plain");
            return;
        }
        action.run();
    }

    private static String executionDefinitionId(String path) {
        String prefix = "/events/";
        String suffix = "/executions";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String definitionId = path.substring(prefix.length(), path.length() - suffix.length());
        return definitionId.isBlank() || definitionId.contains("/") ? null : definitionId;
    }

    private static void text(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /** Authenticated request callback allowed to throw an I/O failure. */
    @FunctionalInterface
    private interface Handler {
        void handle(Actor actor) throws IOException;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** Callback used by the HTTP adapter to create an immediate event execution. */
    @FunctionalInterface
    public interface EventTrigger {
        /**
         * Starts a loaded event definition.
         *
         * @param definitionId event definition identifier
         * @return newly created execution identifier
         * @throws IllegalArgumentException when the definition is unknown
         */
        UUID start(String definitionId);
    }
}
