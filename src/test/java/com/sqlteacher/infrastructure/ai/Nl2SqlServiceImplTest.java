package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Nl2SqlServiceImplTest {
    private static final AiConfiguration CONFIG = new AiConfiguration(
        URI.create("http://localhost:11434"),
        Duration.ofMillis(2000),
        Duration.ofMillis(30000),
        "test-model"
    );

    private static final DatabaseMetadataService EMPTY_METADATA_SERVICE = connectionId -> List.of();
    private static final LearningEventService NO_OP_EVENT_SERVICE = new NoOpLearningEventService();

    @Test
    void shouldReturnParsedResultWhenJsonIsValid() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT * FROM student\", \"intent\": \"QUERY\", \"explanation\": \"查询所有学生\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询所有学生", "demo"));

        assertEquals("SELECT * FROM student", result.sqlDraft());
        assertEquals("QUERY", result.intent());
        assertEquals("查询所有学生", result.explanation());
        assertEquals("test-model", result.model());
        assertEquals("v3", result.promptVersion());
    }

    @Test
    void shouldReturnErrorWhenJsonIsInvalid() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "not valid json",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询所有学生", "demo"));

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

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询所有学生", "demo"));

        assertTrue(result.sqlDraft().isEmpty());
        assertTrue(result.intent().isEmpty());
        assertEquals("Ollama unavailable", result.explanation());
    }

    @Test
    void shouldThrowWhenNaturalLanguageIsBlank() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success("{}", "test-model"));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.generate(new Nl2SqlRequest("", "demo"));
        });
        assertTrue(ex.getMessage().contains("naturalLanguage"));
    }

    @Test
    void shouldThrowWhenConnectionIdIsBlank() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success("{}", "test-model"));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.generate(new Nl2SqlRequest("查询所有学生", ""));
        });
        assertTrue(ex.getMessage().contains("connectionId"));
    }

    @Test
    void shouldUseDynamicTableSchemaFromMetadataService() {
        DatabaseMetadataService dynamicMetadataService = connectionId -> List.of(
            new DatabaseTable("employee", List.of(
                new DatabaseColumn("id", "INTEGER", false, true),
                new DatabaseColumn("name", "TEXT", false, false),
                new DatabaseColumn("salary", "INTEGER", true, false)
            ))
        );

        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT name, salary FROM employee\", \"intent\": \"QUERY\", \"explanation\": \"查询员工姓名和工资\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, dynamicMetadataService, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询员工姓名和工资", "demo"));

        assertEquals("SELECT name, salary FROM employee", result.sqlDraft());
        assertEquals("QUERY", result.intent());
    }

    @Test
    void shouldFallBackToDefaultSchemaWhenMetadataServiceFails() {
        DatabaseMetadataService failingMetadataService = connectionId -> {
            throw new RuntimeException("Metadata service error");
        };

        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT name FROM student\", \"intent\": \"QUERY\", \"explanation\": \"查询学生姓名\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, failingMetadataService, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询学生姓名", "demo"));

        assertEquals("SELECT name FROM student", result.sqlDraft());
        assertEquals("QUERY", result.intent());
    }

    @Test
    void shouldReturnStructurallyValidNonSelectDraftForApplicationSafetyAssessment() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"INSERT INTO student (name) VALUES ('test')\", \"intent\": \"QUERY\", \"explanation\": \"插入学生\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("插入学生", "demo"));

        assertEquals("INSERT INTO student (name) VALUES ('test')", result.sqlDraft());
    }

    @Test
    void shouldReturnStructurallyValidMultiStatementDraftForApplicationSafetyAssessment() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT * FROM student; DROP TABLE student\", \"intent\": \"QUERY\", \"explanation\": \"查询并删除\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询并删除", "demo"));

        assertEquals("SELECT * FROM student; DROP TABLE student", result.sqlDraft());
    }

    @Test
    void shouldAllowSemicolonInsideSelectStringLiteral() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT ';' AS marker\", \"intent\": \"QUERY\", \"explanation\": \"查询分号文本\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询分号文本", "demo"));

        assertEquals("SELECT ';' AS marker", result.sqlDraft());
    }

    @Test
    void shouldRejectInvalidIntent() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT * FROM student\", \"intent\": \"INSERT\", \"explanation\": \"查询学生\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询学生", "demo"));

        assertTrue(result.sqlDraft().isEmpty());
        assertTrue(result.explanation().contains("invalid intent"));
    }

    @Test
    void shouldRejectEmptyExplanation() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"SELECT * FROM student\", \"intent\": \"QUERY\", \"explanation\": \"\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询学生", "demo"));

        assertTrue(result.sqlDraft().isEmpty());
        assertTrue(result.explanation().contains("empty explanation"));
    }

    @Test
    void shouldRejectEmptySqlDraft() {
        AiModelProvider mockProvider = new MockProvider(AiCompletionResult.success(
            "{\"sqlDraft\": \"\", \"intent\": \"QUERY\", \"explanation\": \"查询学生\"}",
            "test-model"
        ));

        Nl2SqlServiceImpl service = new Nl2SqlServiceImpl(mockProvider, CONFIG, EMPTY_METADATA_SERVICE, NO_OP_EVENT_SERVICE);
        Nl2SqlPlan result = service.generate(new Nl2SqlRequest("查询学生", "demo"));

        assertTrue(result.sqlDraft().isEmpty());
        assertTrue(result.explanation().contains("empty SQL draft"));
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

        @Override
        public AiCompletionResult explainError(AiCompletionRequest request) {
            return result;
        }
    }

    private static class NoOpLearningEventService implements LearningEventService {
        @Override
        public void recordSqlExecution(String connectionId, boolean successful, String statementType, Duration duration, int resultCount, String errorCode) {
        }

        @Override
        public void recordSqlRiskBlocked(String connectionId, String statementType, com.sqlteacher.application.risk.SqlRiskLevel riskLevel, boolean multiStatement) {
        }

        @Override
        public void recordAiGeneration(String connectionId, boolean successful, String model, String promptVersion, String errorCode) {
        }
    }
}
