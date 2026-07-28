package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record CourseCatalog(String id, String name, String description, ContentStatus status, long version,
                            String createdBy, Instant createdAt, Instant updatedAt) {
    public CourseCatalog {
        if (id == null || id.isBlank() || name == null || name.isBlank() || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Course identity fields must not be blank");
        }
        if (name.length() > 120) throw new IllegalArgumentException("Course name is too long");
        description = description == null ? "" : description.trim();
        status = status == null ? ContentStatus.ACTIVE : status;
        if (version < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Course version and timestamps are required");
        }
    }
}
