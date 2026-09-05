package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseTextDraft;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.domain.SqlTeacherException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExerciseTextDraftingServiceImpl implements ExerciseTextDraftingService {
    /** Drafting prompts are long and reasoning models can be slow, so use a dedicated bound. */
    private static final Duration DRAFT_TIMEOUT = Duration.ofMinutes(3);
    private final AiModelProvider aiModelProvider;
    private final AiConfiguration aiConfiguration;
    private final AiModelSelectionService modelSelectionService;
    private final ExerciseManagementService exerciseManagementService;

    public ExerciseTextDraftingServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        ExerciseManagementService exerciseManagementService
    ) {
        this(
            aiModelProvider,
            aiConfiguration,
            AiModelSelectionService.fixed(aiConfiguration.defaultModel()),
            exerciseManagementService
        );
    }

    public ExerciseTextDraftingServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        AiModelSelectionService modelSelectionService,
        ExerciseManagementService exerciseManagementService
    ) {
        this.aiModelProvider = Objects.requireNonNull(aiModelProvider, "aiModelProvider must not be null");
        this.aiConfiguration = Objects.requireNonNull(aiConfiguration, "aiConfiguration must not be null");
        this.modelSelectionService = Objects.requireNonNull(
            modelSelectionService,
            "modelSelectionService must not be null"
        );
        this.exerciseManagementService = Objects.requireNonNull(
            exerciseManagementService,
            "exerciseManagementService must not be null"
        );
    }

    @Override
    public ExerciseTextDraft draft(String freeText) {
        if (freeText == null || freeText.isBlank()) {
            throw new IllegalArgumentException("freeText must not be blank");
        }
        String model = resolveSelectedModel();
        if (model.isBlank()) {
            throw new SqlTeacherException(
                "EXERCISE_DRAFT_UNAVAILABLE",
                "No local Ollama model is installed. Install a model or refresh the model list."
            );
        }
        String prompt = PromptTemplateLoader.render("/prompts/exercise-draft-v1.txt", Map.of(
            "freeText", freeText
        ));
        AiCompletionResult result;
        try {
            result = aiModelProvider.complete(
                new AiCompletionRequest(model, prompt, DRAFT_TIMEOUT)
            );
        } catch (RuntimeException error) {
            throw new SqlTeacherException(
                "EXERCISE_DRAFT_UNAVAILABLE",
                "AI provider failed: " + error.getClass().getSimpleName(),
                error
            );
        }
        if (!result.success()) {
            throw new SqlTeacherException("EXERCISE_DRAFT_UNAVAILABLE", result.errorMessage());
        }
        String text = extractDsl(result.content());
        try {
            exerciseManagementService.parsePackage(text);
        } catch (SqlTeacherException error) {
            throw new SqlTeacherException(
                "EXERCISE_DRAFT_INVALID",
                "AI draft failed deterministic validation: " + error.getMessage()
            );
        }
        return new ExerciseTextDraft(text, result.model());
    }

    private String resolveSelectedModel() {
        String preferred = aiModelProvider.preferredModel();
        if (!preferred.isBlank()) {
            return preferred;
        }
        AiModelSelection selection = modelSelectionService.current();
        if (!selection.hasSelection()) {
            selection = modelSelectionService.refresh();
        }
        String selected = selection.selectedModel();
        return selected == null || selected.isBlank() ? aiConfiguration.defaultModel() : selected;
    }

    private static String extractDsl(String content) {
        String stripped = content == null ? "" : content.strip();
        if (stripped.isEmpty()) {
            return stripped;
        }
        String jsonValue = unwrapJson(stripped);
        if (jsonValue != null) {
            return stripFences(jsonValue)
                .replace("\\n", "\n")
                .replace("\\[", "[")
                .replace("\\]", "]")
                .strip();
        }
        return stripFences(stripped);
    }

    private static String unwrapJson(String content) {
        try {
            JsonNode root = new ObjectMapper().readTree(content);
            if (root.isTextual()) {
                return root.asText();
            }
            JsonNode dsl = root.path("dsl");
            if (dsl.isTextual()) {
                return dsl.asText();
            }
            JsonNode text = root.path("text");
            if (text.isTextual()) {
                return text.asText();
            }
        } catch (JsonProcessingException ignored) {
            // Not JSON; treat the content as raw package text.
        }
        return null;
    }

    private static String stripFences(String content) {
        List<String> lines = content.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().startsWith("```")) {
            return content.strip();
        }
        List<String> withoutFences = new ArrayList<>();
        for (String line : lines) {
            if (!line.strip().startsWith("```")) {
                withoutFences.add(line);
            }
        }
        return String.join("\n", withoutFences).strip();
    }
}
