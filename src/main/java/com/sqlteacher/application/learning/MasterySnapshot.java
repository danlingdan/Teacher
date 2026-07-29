package com.sqlteacher.application.learning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MasterySnapshot(
    String ownerId,
    String knowledgePoint,
    MasteryLevel level,
    int attempts,
    int passes,
    int failures,
    int hintsUsed,
    int masteryPercent,
    List<DiagnosisReasonCode> reasons,
    List<MasteryEvidence> evidence,
    String policyVersion,
    Instant updatedAt
) {
    public MasterySnapshot {
        ownerId = required(ownerId, "ownerId");
        knowledgePoint = required(knowledgePoint, "knowledgePoint");
        Objects.requireNonNull(level, "level must not be null");
        if (attempts < 0 || passes < 0 || failures < 0 || hintsUsed < 0 || passes + failures > attempts
            || masteryPercent < 0 || masteryPercent > 100) {
            throw new IllegalArgumentException("Mastery counters are invalid");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        policyVersion = required(policyVersion, "policyVersion");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
