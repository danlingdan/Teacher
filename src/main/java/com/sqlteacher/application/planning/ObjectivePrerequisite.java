package com.sqlteacher.application.planning;

import java.time.Instant;

public record ObjectivePrerequisite(String objectiveId, String prerequisiteObjectiveId, Instant createdAt) {
    public ObjectivePrerequisite {
        objectiveId = required(objectiveId, "objectiveId");
        prerequisiteObjectiveId = required(prerequisiteObjectiveId, "prerequisiteObjectiveId");
        if (objectiveId.equals(prerequisiteObjectiveId)) {
            throw new IllegalArgumentException("An objective cannot depend on itself");
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
