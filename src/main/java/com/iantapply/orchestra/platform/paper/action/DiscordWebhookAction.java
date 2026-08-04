package com.iantapply.orchestra.platform.paper.action;

import com.iantapply.orchestra.api.ActionContext;
import com.iantapply.orchestra.api.OrchestraAction;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Sends action messages to a configured Discord webhook asynchronously. */
final class DiscordWebhookAction implements OrchestraAction {
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public CompletionStage<Void> execute(ActionContext context) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(context.getString("url")))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody(context.getString("message")), StandardCharsets.UTF_8))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Webhook HTTP " + response.statusCode());
            }
            return null;
        });
    }

    private static String jsonBody(String message) {
        String escaped = message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"content\":\"" + escaped + "\"}";
    }
}
