package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record KnowledgePoint(String id, String courseId, String sectionId, String name, String description,
                             int sortOrder, ContentStatus status, long version, Instant createdAt, Instant updatedAt) {
    public KnowledgePoint {
        if (id == null || id.isBlank() || courseId == null || courseId.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Knowledge point identity fields must not be blank");
        }
        if (name.length() > 120 || sortOrder < 0 || version < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Knowledge point fields are invalid");
        }
        description = description == null ? "" : description.trim();
        status = status == null ? ContentStatus.ACTIVE : status;
    }
}
