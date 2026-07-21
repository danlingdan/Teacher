package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.OpenAiCompatibleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** OpenAI Chat Completions compatible provider. Never logs the supplied API key. */
public final class OpenAiCompatibleModelProvider implements AiModelProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelProvider.class);

    private final OpenAiCompatibleConfiguration configuration;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleModelProvider(OpenAiCompatibleConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        char[] key = configuration.apiKey();
        try {
            String body = objectMapper.writeValueAsString(new ChatRequest(
                configuration.model(),
                List.of(new ChatMessage("user", request.prompt())),
                new ResponseFormat("json_object"),
                0.1
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(configuration.endpoint())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + new String(key))
                .timeout(request.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Network AI returned HTTP {}", response.statusCode());
                return AiCompletionResult.failure("Network AI request failed (HTTP " + response.statusCode() + ")", configuration.model());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) {
                return AiCompletionResult.failure("Network AI returned an empty response", configuration.model());
            }
            return AiCompletionResult.success(content, configuration.model());
        } catch (HttpTimeoutException ex) {
            return AiCompletionResult.failure("Network AI request timed out", configuration.model());
        } catch (IOException ex) {
            log.warn("Network AI communication failed: {}", ex.getClass().getSimpleName());
            return AiCompletionResult.failure("Network AI communication failed", configuration.model());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AiCompletionResult.failure("Network AI request was interrupted", configuration.model());
        } finally {
            Arrays.fill(key, '\0');
        }
    }

    private record ChatRequest(String model, List<ChatMessage> messages, ResponseFormat response_format, double temperature) { }
    private record ChatMessage(String role, String content) { }
    private record ResponseFormat(String type) { }
}
