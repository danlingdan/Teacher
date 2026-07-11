package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OllamaAiModelProvider implements AiModelProvider {
    private final AiConfiguration properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean serviceAvailable = new AtomicBoolean(true);

    public OllamaAiModelProvider(AiConfiguration properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.healthTimeout())
            .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (!serviceAvailable.get()) {
            return AiCompletionResult.failure("Ollama service is unavailable, please check local model status", request.model());
        }

        try {
            String requestBody = objectMapper.writeValueAsString(new GenerateRequest(
                request.model(),
                request.prompt(),
                false,
                1
            ));

            HttpRequest httpRequest = HttpRequest.newBuilder(properties.generateEndpoint())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "application/json")
                .timeout(request.timeout())
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                serviceAvailable.set(false);
                return AiCompletionResult.failure("Ollama returned HTTP " + response.statusCode(), request.model());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.has("response") ? root.get("response").asText("") : "";

            if (content.isBlank()) {
                return AiCompletionResult.failure("Ollama returned empty response", request.model());
            }

            serviceAvailable.set(true);
            return AiCompletionResult.success(content, request.model());
        } catch (IOException ex) {
            serviceAvailable.set(false);
            return AiCompletionResult.failure("Ollama service unavailable: " + ex.getClass().getSimpleName(), request.model());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AiCompletionResult.failure("Request interrupted", request.model());
        }
    }

    private record GenerateRequest(String model, String prompt, boolean stream, int num_predict) {
    }
}