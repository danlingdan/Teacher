package com.sqlteacher.application.planning;

import java.util.Objects;

public record StudyPlanAction(String id, String objectiveId, StudyPlanActionType type, String title,
                              String description, ObjectiveResourceType resourceType, String resourceId,
                              StudyPlanReasonCode reasonCode, int priority) {
    public StudyPlanAction {
        id = required(id, "id");
        objectiveId = required(objectiveId, "objectiveId");
        Objects.requireNonNull(type, "type must not be null");
        title = required(title, "title");
        description = required(description, "description");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        resourceId = required(resourceId, "resourceId");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (priority < 1 || priority > 100) throw new IllegalArgumentException("priority must be between 1 and 100");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
