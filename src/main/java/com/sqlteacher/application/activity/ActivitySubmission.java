package com.sqlteacher.application.activity;

import java.time.Instant;
import java.util.Objects;

public record ActivitySubmission(String sessionId, String evaluationId,
                                 ActivityEvaluationResult evaluation, Instant occurredAt) {
    public ActivitySubmission {
        sessionId = required(sessionId, "sessionId");
        evaluationId = required(evaluationId, "evaluationId");
        evaluation = Objects.requireNonNull(evaluation, "evaluation must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
