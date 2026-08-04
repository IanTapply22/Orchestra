package com.iantapply.orchestra.web;

import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.security.Permission;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.Executors;

/** Small authenticated HTTP server exposing health and Prometheus metrics. */
public final class OrchestraHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, Actor> tokens;

    /**
     * Creates the HTTP listener without starting it.
     *
     * @param address bind address
     * @param metrics metrics registry exposed at {@code /metrics}
     * @param tokens bearer-token to actor mappings
     * @throws IOException when the listening server cannot be created
     */
    public OrchestraHttpServer(InetSocketAddress address, MetricsRegistry metrics, Map<String, Actor> tokens)
            throws IOException {
        this.server = HttpServer.create(address, 64);
        this.tokens = Map.copyOf(tokens);

        server.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("orchestra-http-", 0).factory()));
        server.createContext("/health", exchange -> text(exchange, 200, "ok\n", "text/plain"));
        server.createContext(
                "/metrics",
                exchange -> authenticated(
                        exchange,
                        Permission.VIEW,
                        _ -> text(exchange, 200, metrics.prometheus(), "text/plain; version=0.0.4")));
    }

    /** Starts accepting HTTP requests. */
    public void start() {
        server.start();
    }

    /** Stops accepting requests after a one-second grace period. */
    @Override
    public void close() {
        server.stop(1);
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
}
