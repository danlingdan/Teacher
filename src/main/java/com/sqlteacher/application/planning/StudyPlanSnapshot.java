package com.sqlteacher.application.planning;

import java.time.Instant;
import java.util.List;

public record StudyPlanSnapshot(String ownerId, String courseId, String policyVersion,
                                Instant generatedAt, Instant expiresAt, List<StudyPlanAction> actions) {
    public StudyPlanSnapshot {
        ownerId = required(ownerId, "ownerId");
        courseId = required(courseId, "courseId");
        policyVersion = required(policyVersion, "policyVersion");
        if (generatedAt == null || expiresAt == null || !expiresAt.isAfter(generatedAt)) {
            throw new IllegalArgumentException("Study plan timestamps are invalid");
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (actions.size() > 7) throw new IllegalArgumentException("Study plan cannot contain more than 7 actions");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
