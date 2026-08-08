package com.sqlteacher.domain.activity;

import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.util.List;
import java.util.Objects;

public record SqlActivitySpecification(
    ExerciseDataset dataset,
    String knowledgePoint,
    String referenceSql,
    ExerciseEvaluationRule evaluationRule,
    List<String> hints,
    int formatVersion
) implements ActivitySpecification {
    public SqlActivitySpecification {
        dataset = Objects.requireNonNull(dataset, "dataset must not be null");
        knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
        referenceSql = requireText(referenceSql, "referenceSql");
        evaluationRule = Objects.requireNonNull(evaluationRule, "evaluationRule must not be null");
        hints = List.copyOf(Objects.requireNonNull(hints, "hints must not be null"));
        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
    }

    @Override
    public ActivityType type() {
        return ActivityType.SQL;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
