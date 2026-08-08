package com.sqlteacher.domain.course;

import java.util.Objects;

public record LearningOutcome(String id, String courseId, String description, String expectedLevel) {
    public LearningOutcome {
        id = required(id, "id");
        courseId = required(courseId, "courseId");
        description = required(description, "description");
        expectedLevel = required(expectedLevel, "expectedLevel");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
