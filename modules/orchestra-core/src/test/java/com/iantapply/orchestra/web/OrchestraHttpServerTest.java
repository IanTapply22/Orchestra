package com.iantapply.orchestra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.audit.InMemoryAuditRepository;
import com.iantapply.orchestra.metrics.MetricsRegistry;
import com.iantapply.orchestra.security.Actor;
import com.iantapply.orchestra.security.Role;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OrchestraHttpServerTest {
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void healthIsPublicAndMetricsRequireAValidBearerToken() throws Exception {
        int port = freePort();
        MetricsRegistry metrics = new MetricsRegistry();
        metrics.increment("orchestra_events_total");
        try (OrchestraHttpServer server = new OrchestraHttpServer(
                new InetSocketAddress("127.0.0.1", port),
                metrics,
                Map.of("secret-token", new Actor("test", Role.VIEWER)),
                ignored -> UUID.randomUUID())) {
            server.start();

            HttpResponse<String> health = get(port, "/health", null);
            HttpResponse<String> missing = get(port, "/metrics", null);
            HttpResponse<String> invalid = get(port, "/metrics", "wrong");
            HttpResponse<String> authorized = get(port, "/metrics", "secret-token");

            assertEquals(200, health.statusCode());
            assertEquals("ok\n", health.body());
            assertEquals(401, missing.statusCode());
            assertEquals(
                    "Bearer", missing.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals(401, invalid.statusCode());
            assertEquals(200, authorized.statusCode());
            assertEquals("orchestra_events_total 1\n", authorized.body());
            assertTrue(authorized
                    .headers()
                    .firstValue("Content-Type")
                    .orElseThrow()
                    .contains("version=0.0.4"));
            assertEquals(
                    "no-store", authorized.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    void operatorCanTriggerAnEventAndReceivesTheExecutionId() throws Exception {
        int port = freePort();
        UUID executionId = UUID.randomUUID();
        AtomicReference<String> triggeredEvent = new AtomicReference<>();
        InMemoryAuditRepository audit = new InMemoryAuditRepository(10);
        try (OrchestraHttpServer server = new OrchestraHttpServer(
                new InetSocketAddress("127.0.0.1", port),
                new MetricsRegistry(),
                Map.of(
                        "viewer-token", new Actor("viewer", Role.VIEWER),
                        "operator-token", new Actor("operator", Role.OPERATOR)),
                definitionId -> {
                    triggeredEvent.set(definitionId);
                    return executionId;
                },
                audit,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))) {
            server.start();

            HttpResponse<String> missing = post(port, "/events/weekend_double_xp/executions", null);
            HttpResponse<String> forbidden = post(port, "/events/weekend_double_xp/executions", "viewer-token");
            HttpResponse<String> accepted = post(port, "/events/weekend_double_xp/executions", "operator-token");

            assertEquals(401, missing.statusCode());
            assertEquals(403, forbidden.statusCode());
            assertEquals(202, accepted.statusCode());
            assertEquals("weekend_double_xp", triggeredEvent.get());
            assertEquals(
                    "{\"execution_id\":\"%s\",\"definition_id\":\"weekend_double_xp\"}\n".formatted(executionId),
                    accepted.body());
            assertTrue(
                    accepted.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
            var entry = audit.recent(1).getFirst();
            assertEquals(Instant.EPOCH, entry.occurredAt());
            assertEquals("operator", entry.actor());
            assertEquals("start_execution", entry.action());
            assertEquals("event:weekend_double_xp", entry.resource());
            assertEquals("execution:" + executionId, entry.detail());
        }
    }

    @Test
    void triggerEndpointRejectsUnknownEventsInvalidPathsAndOtherMethods() throws Exception {
        int port = freePort();
        try (OrchestraHttpServer server = new OrchestraHttpServer(
                new InetSocketAddress("127.0.0.1", port),
                new MetricsRegistry(),
                Map.of("operator-token", new Actor("operator", Role.OPERATOR)),
                ignored -> {
                    throw new IllegalArgumentException("Unknown event");
                })) {
            server.start();

            HttpResponse<String> unknown = post(port, "/events/missing/executions", "operator-token");
            HttpResponse<String> invalidPath = post(port, "/events/missing", "operator-token");
            HttpResponse<String> wrongMethod = get(port, "/events/missing/executions", "operator-token");

            assertEquals(404, unknown.statusCode());
            assertEquals(404, invalidPath.statusCode());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());

            HttpResponse<String> healthPost = post(port, "/health", null);
            HttpResponse<String> metricsPost = post(port, "/metrics", "operator-token");
            assertEquals(405, healthPost.statusCode());
            assertEquals(405, metricsPost.statusCode());
            assertEquals("GET", metricsPost.headers().firstValue("Allow").orElseThrow());
        }
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
