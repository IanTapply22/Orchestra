package com.iantapply.orchestra.platform.paper.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.iantapply.orchestra.api.ActionContext;
import com.iantapply.orchestra.api.ActionSpec;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DiscordWebhookActionTest {
    @Test
    void postsEscapedJsonAndAcceptsSuccessfulResponses() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            new DiscordWebhookAction()
                    .execute(context(server, "quote \" and\nline"))
                    .toCompletableFuture()
                    .join();
            assertEquals("{\"content\":\"quote \\\" and\\nline\"}", body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonSuccessfulResponses() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        try {
            assertThrows(CompletionException.class, () -> new DiscordWebhookAction()
                    .execute(context(server, "message"))
                    .toCompletableFuture()
                    .join());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", handler);
        server.start();
        return server;
    }

    private static ActionContext context(HttpServer server, String message) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
        ActionSpec action = new ActionSpec("webhook", "discord_webhook", Map.of("url", url, "message", message), null);
        StageDefinition stage =
                new StageDefinition("stage", Duration.ZERO, Duration.ofSeconds(2), List.of(), List.of(action));
        EventDefinition event = new EventDefinition("test_event", "Test", TargetSelector.ALL_ONLINE, List.of(stage));
        return new ActionContext(UUID.randomUUID(), event, stage, action, "server", Instant.now(), Map.of());
    }
}
