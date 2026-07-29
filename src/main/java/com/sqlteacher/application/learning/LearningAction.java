package com.sqlteacher.application.learning;

import java.time.Instant;
import java.util.Objects;

public record LearningAction(
    String id,
    LearningActionType type,
    String title,
    String description,
    String exerciseId,
    String knowledgePoint,
    DiagnosisReasonCode reason,
    int priority,
    Instant updatedAt,
    boolean dismissed
) {
    public LearningAction {
        id = required(id, "id");
        Objects.requireNonNull(type, "type must not be null");
        title = required(title, "title");
        description = required(description, "description");
        exerciseId = exerciseId == null ? "" : exerciseId.trim();
        knowledgePoint = knowledgePoint == null ? "" : knowledgePoint.trim();
        Objects.requireNonNull(reason, "reason must not be null");
        if (priority < 1 || priority > 100) throw new IllegalArgumentException("priority must be between 1 and 100");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
