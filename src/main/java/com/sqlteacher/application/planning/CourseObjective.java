package com.sqlteacher.application.planning;

import com.sqlteacher.application.collaboration.ContentStatus;

import java.time.Instant;

public record CourseObjective(String id, String courseId, String title, String description,
                              String completionCriteria, int sortOrder, ContentStatus status,
                              long version, String createdBy, Instant createdAt, Instant updatedAt) {
    public CourseObjective {
        id = required(id, "id");
        courseId = required(courseId, "courseId");
        title = required(title, "title");
        createdBy = required(createdBy, "createdBy");
        description = optional(description);
        completionCriteria = required(completionCriteria, "completionCriteria");
        status = status == null ? ContentStatus.ACTIVE : status;
        if (title.length() > 120 || description.length() > 1_000 || completionCriteria.length() > 500
            || sortOrder < 0 || version < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Course objective fields are invalid");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
