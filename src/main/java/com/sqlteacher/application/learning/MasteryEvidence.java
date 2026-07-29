package com.sqlteacher.application.learning;

import java.time.Instant;
import java.util.Objects;

public record MasteryEvidence(
    String sourceId,
    String exerciseId,
    String kind,
    boolean successful,
    String errorCode,
    Instant occurredAt
) {
    public MasteryEvidence {
        sourceId = required(sourceId, "sourceId");
        exerciseId = required(exerciseId, "exerciseId");
        kind = required(kind, "kind");
        errorCode = errorCode == null ? "" : errorCode.trim();
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
