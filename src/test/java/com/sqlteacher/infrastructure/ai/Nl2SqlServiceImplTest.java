package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Nl2SqlServiceImplTest {
    @Test
    void shouldReturnParsedResultWhenJsonIsValid() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT * FROM student\", \"intent\": \"QUERY\", \"explanation\": \"查询所有学生\"}",
            "test-model"
        ));

        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(2000),
            Duration.ofMillis(30000),
            "test-model"
        );

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, config);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest(
            "查询所有学生",
            "demo"
        ));

        assertEquals("SELECT * FROM student", result.sqlDraft());
        assertEquals("QUERY", result.intent());
        assertEquals("查询所有学生", result.explanation());
        assertEquals("test-model", result.model());
    }

    @Test
    void shouldReturnErrorWhenJsonIsInvalid() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "not valid json",
            "test-model"
        ));

        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(2000),
            Duration.ofMillis(30000),
            "test-model"
        );

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, config);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest(
            "查询所有学生",
            "demo"
        ));

        assertTrue(result.sqlDraft().isEmpty());
        assertTrue(result.intent().isEmpty());
        assertTrue(result.explanation().contains("Failed to parse"));
    }

    @Test
    void shouldReturnErrorWhenProviderFails() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.failure(
            "Ollama unavailable",
            "test-model"
        ));

        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(2000),
            Duration.ofMillis(30000),
            "test-model"
        );

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, config);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest(
            "查询所有学生",
            "demo"
        ));

        assertTrue(result.sqlDraft().isEmpty());
        assertTrue(result.intent().isEmpty());
        assertEquals("Ollama unavailable", result.explanation());
    }

    @Test
    void shouldThrowWhenNaturalLanguageIsBlank() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success("{}", "test-model"));
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(2000),
            Duration.ofMillis(30000),
            "test-model"
        );

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, config);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.generate(new Nl2SqlRequest("", "demo"));
        });
        assertTrue(ex.getMessage().contains("naturalLanguage"));
    }

    @Test
    void shouldThrowWhenConnectionIdIsBlank() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success("{}", "test-model"));
        AiConfiguration config = new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofMillis(2000),
            Duration.ofMillis(30000),
            "test-model"
        );

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, config);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.generate(new Nl2SqlRequest("查询所有学生", ""));
        });
        assertTrue(ex.getMessage().contains("connectionId"));
    }

    private static class MockProvider implements AiModelProvider {
        private final AiCompletionResult result;

        MockProvider(AiCompletionResult result) {
            this.result = result;
        }

        @Override
        public AiCompletionResult complete(AiCompletionRequest request) {
            return result;
        }
    }
}