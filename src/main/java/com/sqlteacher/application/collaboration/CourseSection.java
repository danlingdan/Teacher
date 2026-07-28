package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record CourseSection(String id, String courseId, String name, int sortOrder, ContentStatus status,
                            long version, Instant createdAt, Instant updatedAt) {
    public CourseSection {
        if (id == null || id.isBlank() || courseId == null || courseId.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Section identity fields must not be blank");
        }
        if (name.length() > 120 || sortOrder < 0 || version < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Section fields are invalid");
        }
        status = status == null ? ContentStatus.ACTIVE : status;
    }
}
