package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.config.AiConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaAiModelProviderTest {
    @Test
    void shouldReturnFailureWhenOllamaIsUnavailable() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://127.0.0.1:9"),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            "test-model"
        );
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config);

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
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config);

        AiCompletionResult result = provider.complete(new AiCompletionRequest(
            "test-model",
            "test prompt",
            Duration.ofMillis(10)
        ));

        assertFalse(result.success());
    }

    @Test
    void shouldReturnFailureWhenModelIsBlank() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            "test-model"
        );
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config);

        try {
            provider.complete(new AiCompletionRequest(
                "",
                "test prompt",
                Duration.ofMillis(100)
            ));
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("model"));
        }
    }

    @Test
    void shouldReturnFailureWhenPromptIsBlank() {
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(100),
            Duration.ofMillis(100),
            "test-model"
        );
        OllamaAiModelProvider provider = new OllamaAiModelProvider(config);

        try {
            provider.complete(new AiCompletionRequest(
                "test-model",
                "",
                Duration.ofMillis(100)
            ));
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("prompt"));
        }
    }
}