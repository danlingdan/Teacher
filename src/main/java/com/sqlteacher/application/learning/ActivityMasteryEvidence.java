package com.sqlteacher.application.learning;

import com.sqlteacher.domain.activity.ActivityType;

import java.time.Instant;
import java.util.Objects;

public record ActivityMasteryEvidence(
    String sourceId,
    String activityId,
    ActivityType activityType,
    String kind,
    boolean successful,
    String reasonCode,
    String evaluatorVersion,
    String evidenceVersion,
    Instant occurredAt
) {
    public ActivityMasteryEvidence {
        sourceId = required(sourceId, "sourceId");
        activityId = required(activityId, "activityId");
        activityType = Objects.requireNonNull(activityType, "activityType must not be null");
        kind = required(kind, "kind");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        evaluatorVersion = required(evaluatorVersion, "evaluatorVersion");
        evidenceVersion = required(evidenceVersion, "evidenceVersion");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
