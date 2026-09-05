package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseTextDraft;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.JdbcExerciseManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseTextDraftingServiceImplTest {
    @TempDir
    Path tempDir;

    private static final String VALID_DSL = String.join("\n",
        "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
        "===[EXERCISE]===", "TITLE: 查询", "KNOWLEDGE: 基础查询", "DIFFICULTY: BEGINNER",
        "DATASET: d1", "DESCRIPTION:", "说明", "SQL:", "select x from t", "RULE: EXACT"
    );

    @Test
    void shouldReturnDeterministicallyValidatedDraft() {
        MockProvider provider = new MockProvider(AiCompletionResult.success(VALID_DSL, "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        ExerciseTextDraft draft = service.draft("帮我出题");

        assertEquals(VALID_DSL, draft.text());
        assertEquals("test-model", draft.model());
        assertTrue(provider.requests.get(0).prompt().contains("帮我出题"));
        assertEquals(Duration.ofMinutes(3), provider.requests.get(0).timeout());
    }

    @Test
    void shouldStripMarkdownFencesBeforeValidation() {
        String fenced = "```\n" + VALID_DSL + "\n```";
        MockProvider provider = new MockProvider(AiCompletionResult.success(fenced, "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        ExerciseTextDraft draft = service.draft("帮我出题");

        assertEquals(VALID_DSL, draft.text());
    }

    @Test
    void shouldUnwrapJsonObjectDslField() throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("dsl", VALID_DSL));
        MockProvider provider = new MockProvider(AiCompletionResult.success(json, "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        ExerciseTextDraft draft = service.draft("帮我出题");

        assertEquals(VALID_DSL, draft.text());
    }

    @Test
    void shouldNormalizeEscapedBracketsInJsonDsl() throws Exception {
        String escaped = VALID_DSL.replace("===[DATASET]===", "===\\[DATASET\\]===");
        String json = new ObjectMapper().writeValueAsString(Map.of("dsl", escaped));
        MockProvider provider = new MockProvider(AiCompletionResult.success(json, "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        ExerciseTextDraft draft = service.draft("帮我出题");

        assertEquals(VALID_DSL, draft.text());
    }

    @Test
    void shouldNormalizeOverEscapedNewlinesAndBracketsInJsonDsl() throws Exception {
        String mangled = VALID_DSL
            .replace("===[DATASET]===", "===\\[DATASET\\]===")
            .replace("\n", "\\n");
        String json = new ObjectMapper().writeValueAsString(Map.of("dsl", mangled));
        MockProvider provider = new MockProvider(AiCompletionResult.success(json, "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        ExerciseTextDraft draft = service.draft("帮我出题");

        assertEquals(VALID_DSL, draft.text());
    }

    @Test
    void shouldRejectDraftThatFailsDeterministicValidation() {
        MockProvider provider = new MockProvider(
            AiCompletionResult.success("not a valid package", "test-model")
        );
        ExerciseTextDraftingServiceImpl service = service(provider);

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> service.draft("帮我出题"));

        assertEquals("EXERCISE_DRAFT_INVALID", error.errorCode());
    }

    @Test
    void shouldFailSafelyWhenProviderReturnsFailure() {
        MockProvider provider = new MockProvider(AiCompletionResult.failure("Ollama down", "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> service.draft("帮我出题"));

        assertEquals("EXERCISE_DRAFT_UNAVAILABLE", error.errorCode());
    }

    @Test
    void shouldFailSafelyWhenProviderThrows() {
        AiModelProvider provider = request -> {
            throw new IllegalStateException("boom");
        };
        ExerciseTextDraftingServiceImpl service = service(provider);

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> service.draft("帮我出题"));

        assertEquals("EXERCISE_DRAFT_UNAVAILABLE", error.errorCode());
    }

    @Test
    void shouldRejectBlankFreeText() {
        MockProvider provider = new MockProvider(AiCompletionResult.success(VALID_DSL, "test-model"));
        ExerciseTextDraftingServiceImpl service = service(provider);

        assertThrows(IllegalArgumentException.class, () -> service.draft("   "));
    }

    @Test
    void shouldFallBackToConfiguredModelWhenProviderHasNoPreference() {
        MockProvider provider = new MockProvider(AiCompletionResult.success(VALID_DSL, "configured-model")) {
            @Override
            public String preferredModel() {
                return "";
            }
        };
        ExerciseTextDraftingServiceImpl service = service(provider);

        service.draft("帮我出题");

        assertEquals("configured-model", provider.requests.get(0).model());
    }

    @Test
    void shouldUseSelectedLocalModelWhenProviderHasNoPreference() {
        MockProvider provider = new MockProvider(AiCompletionResult.success(VALID_DSL, "selected-model")) {
            @Override
            public String preferredModel() {
                return "";
            }
        };
        ExerciseTextDraftingServiceImpl service = new ExerciseTextDraftingServiceImpl(
            provider,
            ai(),
            AiModelSelectionService.fixed("selected-model"),
            management()
        );

        service.draft("帮我出题");

        assertEquals("selected-model", provider.requests.get(0).model());
    }

    private ExerciseTextDraftingServiceImpl service(AiModelProvider provider) {
        return new ExerciseTextDraftingServiceImpl(provider, ai(), management());
    }

    private AiConfiguration ai() {
        return new AiConfiguration(
            URI.create("http://localhost:11434"),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            "configured-model"
        );
    }

    private ExerciseManagementService management() {
        return new JdbcExerciseManagementService(new JdbcConnectionFactory(
            new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"))
        ));
    }

    private static class MockProvider implements AiModelProvider {
        final AiCompletionResult result;
        final List<AiCompletionRequest> requests = new ArrayList<>();

        MockProvider(AiCompletionResult result) {
            this.result = result;
        }

        @Override
        public AiCompletionResult complete(AiCompletionRequest request) {
            requests.add(request);
            return result;
        }

        @Override
        public String preferredModel() {
            return "test-model";
        }
    }
}
