package com.sqlteacher.domain.course;

import java.util.Objects;

public record CourseSection(String id, String courseId, String title, int sortOrder) {
    public CourseSection {
        id = required(id, "id");
        courseId = required(courseId, "courseId");
        title = required(title, "title");
        if (sortOrder < 0) throw new IllegalArgumentException("sortOrder must not be negative");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
