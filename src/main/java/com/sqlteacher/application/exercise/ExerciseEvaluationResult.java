package com.sqlteacher.application.exercise;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ExerciseEvaluationResult(
    boolean passed,
    List<EvaluationCriterionResult> criteria,
    String feedback,
    Duration duration,
    String errorCode
) {
    public ExerciseEvaluationResult {
        criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria must not be null"));
        feedback = Objects.requireNonNull(feedback, "feedback must not be null").trim();
        duration = Objects.requireNonNull(duration, "duration must not be null");
        errorCode = errorCode == null ? "" : errorCode.trim();
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (passed && criteria.stream().anyMatch(criterion -> !criterion.passed())) {
            throw new IllegalArgumentException("passed result cannot contain failed criteria");
        }
    }
}
