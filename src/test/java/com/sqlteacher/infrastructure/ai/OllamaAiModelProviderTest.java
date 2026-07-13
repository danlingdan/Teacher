package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiAvailability;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.config.AiConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaAiModelProviderTest {
    @Test
    void shouldReturnFailureWhenOllamaIsUnavailable() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://127.0.0.1:9"),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            "test-model"
        );
        AiStatusService mockStatusService = () -> new AiStatus(AiAvailability.UNAVAILABLE, "ollama", "http://127.0.0.1:9", 0, "unavailable");
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config, mockStatusService);

        AiCompletionResult result = provider.complete(new AiCompletionRequest(
            "test-model",
            "test prompt",
            Duration.ofMillis(100)
        ));

        assertFalse(result.success());
    }

    @Test
    void shouldReturnFailureWhenTimeout() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(10),
            Duration.ofMillis(10),
            "test-model"
        );
        AiStatusService mockStatusService = () -> new AiStatus(AiAvailability.AVAILABLE, "ollama", "http://localhost:11434", 1, "available");
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config, mockStatusService);

        AiCompletionResult result = provider.complete(new AiCompletionRequest(
            "test-model",
            "test prompt",
            Duration.ofMillis(10)
        ));

        assertFalse(result.success());
    }

    @Test
    void shouldThrowWhenModelIsBlank() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            "test-model"
        );
        AiStatusService mockStatusService = () -> new AiStatus(AiAvailability.AVAILABLE, "ollama", "http://localhost:11434", 1, "available");
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config, mockStatusService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            provider.complete(new AiCompletionRequest(
                "",
                "test prompt",
                Duration.ofMillis(100)
            ));
        });
        assertTrue(ex.getMessage().contains("model"));
    }

    @Test
    void shouldThrowWhenPromptIsBlank() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            "test-model"
        );
        AiStatusService mockStatusService = () -> new AiStatus(AiAvailability.AVAILABLE, "ollama", "http://localhost:11434", 1, "available");
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config, mockStatusService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            provider.complete(new AiCompletionRequest(
                "test-model",
                "",
                Duration.ofMillis(100)
            ));
        });
        assertTrue(ex.getMessage().contains("prompt"));
    }

    @Test
    void shouldIncludeFormatJsonAndOptionsInRequest() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        var request = new OllamaAiModelProvider.GenerateRequest(
            "test-model",
            "test prompt",
            false,
            "json",
            new OllamaAiModelProvider.GenerateOptions(2048)
        );
        String json = objectMapper.writeValueAsString(request);

        assertTrue(json.contains("\"format\":\"json\""), "Request should include format: json");
        assertTrue(json.contains("\"options\":{"), "Request should include options object");
        assertTrue(json.contains("\"num_predict\":2048"), "Request should include num_predict in options");
        assertFalse(json.contains("\"num_predict\":1"), "num_predict should not be 1");
    }
}