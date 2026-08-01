package com.sqlteacher.application.planning;

import java.time.Instant;
import java.util.Objects;

public record ObjectiveResourceLink(String objectiveId, ObjectiveResourceType resourceType,
                                    String resourceId, Instant createdAt) {
    public ObjectiveResourceLink {
        objectiveId = required(objectiveId, "objectiveId");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        resourceId = required(resourceId, "resourceId");
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
