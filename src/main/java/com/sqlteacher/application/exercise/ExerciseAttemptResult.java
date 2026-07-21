package com.sqlteacher.application.exercise;

import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.domain.exercise.ExerciseAttemptStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ExerciseAttemptResult(
    String attemptId,
    String sessionId,
    ExerciseAttemptStatus status,
    SqlExecutionResult execution,
    ExerciseEvaluationResult evaluation,
    Instant occurredAt
) {
    public ExerciseAttemptResult {
        attemptId = requireText(attemptId, "attemptId");
        sessionId = requireText(sessionId, "sessionId");
        status = Objects.requireNonNull(status, "status must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if ((status == ExerciseAttemptStatus.PASSED || status == ExerciseAttemptStatus.FAILED)
                && evaluation == null) {
            throw new IllegalArgumentException("submitted attempts require evaluation");
        }
    }

    public Optional<SqlExecutionResult> executionResult() {
        return Optional.ofNullable(execution);
    }

    public Optional<ExerciseEvaluationResult> evaluationResult() {
        return Optional.ofNullable(evaluation);
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
