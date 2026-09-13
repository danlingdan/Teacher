package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.infrastructure.environment.VerificationItem;
import com.sqlteacher.infrastructure.environment.VerificationStatus;
import com.sqlteacher.infrastructure.support.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The single Ollama {@code /api/tags} health probe shared by every Ollama-facing service. */
public final class OllamaHealthClient {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaHealthClient(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.httpClient = HttpClients.create(timeout);
        this.objectMapper = new ObjectMapper();
    }

    public VerificationItem checkHealth() {
        Probe probe = probe(httpClient, objectMapper, endpoint, timeout);
        if (probe.failure() instanceof InterruptedException) {
            return new VerificationItem(
                "Ollama health",
                VerificationStatus.WARNING,
                "health check interrupted"
            );
        }
        if (probe.failure() != null) {
            return VerificationItem.warning("Ollama health", "service not reachable: " + describe(probe.failure()));
        }
        if (!probe.reachable()) {
            return VerificationItem.warning("Ollama health", "HTTP " + probe.statusCode());
        }
        int modelCount = modelCount(probe.models());
        return VerificationItem.passed("Ollama health", "service reachable, models=" + modelCount);
    }

    /** One tags probe result: HTTP status plus parsed models array, or the failure when unreachable. */
    record Probe(int statusCode, JsonNode models, Exception failure) {
        boolean reachable() {
            return failure == null && statusCode == 200;
        }
    }

    /**
     * Performs one GET on the given Ollama tags endpoint. Never throws for IO or interruption:
     * IO failures (including unreadable response bodies) are returned as {@code failure};
     * interruptions re-set the interrupt flag and are returned as {@code failure}.
     */
    static Probe probe(HttpClient httpClient, ObjectMapper objectMapper, URI endpoint, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .GET()
            .timeout(timeout)
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Probe(response.statusCode(), null, null);
            }
            return new Probe(response.statusCode(), objectMapper.readTree(response.body()).path("models"), null);
        } catch (IOException error) {
            return new Probe(-1, null, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Probe(-1, null, error);
        }
    }

    /** Counts entries of a parsed models array; {@code null} or a non-array counts as zero. */
    static int modelCount(JsonNode models) {
        return models != null && models.isArray() ? models.size() : 0;
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }
}
