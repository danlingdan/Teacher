package com.sqlteacher.domain.exercise;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExerciseDefinition(
    String id,
    String title,
    String description,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    String datasetId,
    String referenceSql,
    ExerciseEvaluationRule evaluationRule,
    List<String> hints,
    int version,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
    public ExerciseDefinition {
        id = requireText(id, "id");
        title = requireText(title, "title");
        description = requireText(description, "description");
        knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        datasetId = requireText(datasetId, "datasetId");
        referenceSql = requireText(referenceSql, "referenceSql");
        evaluationRule = Objects.requireNonNull(evaluationRule, "evaluationRule must not be null");
        hints = normalizeHints(hints);
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static List<String> normalizeHints(List<String> values) {
        Objects.requireNonNull(values, "hints must not be null");
        List<String> normalized = values.stream()
            .map(value -> requireText(value, "hint"))
            .distinct()
            .toList();
        if (normalized.size() > 3) {
            throw new IllegalArgumentException("At most three hint levels are supported");
        }
        return normalized;
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
