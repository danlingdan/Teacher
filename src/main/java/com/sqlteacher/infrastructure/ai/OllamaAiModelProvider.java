package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.config.AiConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class OllamaAiModelProvider implements AiModelProvider {
    private static final Logger log = LoggerFactory.getLogger(OllamaAiModelProvider.class);

    private final AiConfiguration properties;
    private final AiStatusService aiStatusService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaAiModelProvider(AiConfiguration properties, AiStatusService aiStatusService) {
        this.properties = properties;
        this.aiStatusService = aiStatusService;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.healthTimeout())
            .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (!aiStatusService.checkStatus().available()) {
            return AiCompletionResult.failure("Ollama service is unavailable, please check local model status", request.model());
        }

        try {
            String requestBody = objectMapper.writeValueAsString(new GenerateRequest(
                request.model(),
                request.prompt(),
                false,
                "json",
                new GenerateOptions(2048)
            ));

            HttpRequest httpRequest = HttpRequest.newBuilder(properties.generateEndpoint())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "application/json")
                .timeout(request.timeout())
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return AiCompletionResult.failure("Ollama returned HTTP " + response.statusCode(), request.model());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.has("response") ? root.get("response").asText("") : "";

            if (content.isBlank()) {
                return AiCompletionResult.failure("Ollama returned empty response", request.model());
            }

            return AiCompletionResult.success(content, request.model());
        } catch (ConnectException ex) {
            log.warn("Ollama connection failed: {}", ex.getMessage());
            return AiCompletionResult.failure("Ollama service is not running. Please start Ollama and try again.", request.model());
        } catch (SocketTimeoutException ex) {
            log.warn("Ollama request timed out");
            return AiCompletionResult.failure("AI request timed out. Please try a simpler query or increase timeout.", request.model());
        } catch (IOException ex) {
            log.warn("Ollama IO error: {}", ex.getClass().getSimpleName(), ex);
            return AiCompletionResult.failure("AI service communication error: " + ex.getClass().getSimpleName(), request.model());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Ollama request interrupted");
            return AiCompletionResult.failure("AI request was interrupted", request.model());
        }
    }

    record GenerateRequest(String model, String prompt, boolean stream, String format, GenerateOptions options) {
    }

    record GenerateOptions(int num_predict) {
    }
}