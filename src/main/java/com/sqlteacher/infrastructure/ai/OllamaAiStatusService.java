package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.infrastructure.config.AiModelProperties;
import com.sqlteacher.infrastructure.environment.VerificationStatus;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class OllamaAiStatusService implements AiStatusService {
    private final AiModelProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaAiStatusService(AiModelProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.healthTimeout())
            .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AiStatus checkStatus() {
        HttpRequest request = HttpRequest.newBuilder(properties.tagsEndpoint())
            .GET()
            .timeout(properties.healthTimeout())
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new AiStatus(
                    VerificationStatus.WARNING,
                    "ollama",
                    properties.ollamaBaseUrl().toString(),
                    0,
                    "Ollama returned HTTP " + response.statusCode()
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            int modelCount = root.path("models").isArray() ? root.path("models").size() : 0;
            return new AiStatus(
                VerificationStatus.PASS,
                "ollama",
                properties.ollamaBaseUrl().toString(),
                modelCount,
                "Ollama service reachable, models=" + modelCount
            );
        } catch (IOException ex) {
            return unavailable(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return unavailable(ex);
        }
    }

    private AiStatus unavailable(Exception ex) {
        return new AiStatus(
            VerificationStatus.WARNING,
            "ollama",
            properties.ollamaBaseUrl().toString(),
            0,
            "Ollama service unavailable: " + ex.getClass().getSimpleName()
        );
    }
}
