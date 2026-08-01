package com.sqlteacher.application.planning;

import java.time.Instant;

public record LearningEvidenceRef(LearningEvidenceType type, String evidenceId, String version,
                                  String contentHash, Instant occurredAt) {
    public LearningEvidenceRef {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        evidenceId = required(evidenceId, "evidenceId");
        version = required(version, "version");
        contentHash = required(contentHash, "contentHash");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
