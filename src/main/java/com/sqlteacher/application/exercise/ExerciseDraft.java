package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.util.List;
import java.util.Objects;

public record ExerciseDraft(
    String id,
    String title,
    String description,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    String datasetId,
    String referenceSql,
    ExerciseEvaluationRule evaluationRule,
    List<String> hints,
    Integer expectedVersion,
    boolean enabled
) {
    public ExerciseDraft {
        id = normalizeOptionalId(id);
        title = requireText(title, "title");
        description = requireText(description, "description");
        knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        datasetId = requireText(datasetId, "datasetId");
        referenceSql = requireText(referenceSql, "referenceSql");
        evaluationRule = Objects.requireNonNull(evaluationRule, "evaluationRule must not be null");
        hints = List.copyOf(Objects.requireNonNull(hints, "hints must not be null"));
        if (expectedVersion != null && expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }

    private static String normalizeOptionalId(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
