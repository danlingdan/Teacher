package com.sqlteacher.application.exercise;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic evaluation outcome. {@code score} is a 0-100 weighted display value over
 * the scored criteria; the passing semantics never change — every criterion must pass.
 * {@code comparison} carries the expected/actual view when the teacher's reveal mode
 * allows it (only after a submission, never before).
 */
public record ExerciseEvaluationResult(
    boolean passed,
    List<EvaluationCriterionResult> criteria,
    String feedback,
    Duration duration,
    String errorCode,
    Integer score,
    ResultComparison comparison
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
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
    }

    /** Compatibility view for pre-v3.3 callers without score or comparison. */
    public ExerciseEvaluationResult(
        boolean passed,
        List<EvaluationCriterionResult> criteria,
        String feedback,
        Duration duration,
        String errorCode
    ) {
        this(passed, criteria, feedback, duration, errorCode, null, null);
    }
}
