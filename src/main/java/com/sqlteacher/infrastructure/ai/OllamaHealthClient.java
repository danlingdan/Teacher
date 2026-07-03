package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.infrastructure.environment.VerificationItem;
import com.sqlteacher.infrastructure.environment.VerificationStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class OllamaHealthClient {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaHealthClient(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public VerificationItem checkHealth() {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .GET()
            .timeout(timeout)
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return VerificationItem.warning("Ollama health", "HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            int modelCount = root.path("models").isArray() ? root.path("models").size() : 0;
            return VerificationItem.passed("Ollama health", "service reachable, models=" + modelCount);
        } catch (IOException ex) {
            return VerificationItem.warning("Ollama health", "service not reachable: " + describe(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new VerificationItem(
                "Ollama health",
                VerificationStatus.WARNING,
                "health check interrupted"
            );
        }
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }
}
