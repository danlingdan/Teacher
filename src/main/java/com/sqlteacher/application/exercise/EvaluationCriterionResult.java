package com.sqlteacher.application.exercise;

import java.util.Objects;

public record EvaluationCriterionResult(
    String criterion,
    boolean passed,
    String feedback
) {
    public EvaluationCriterionResult {
        criterion = requireText(criterion, "criterion");
        feedback = requireText(feedback, "feedback");
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
