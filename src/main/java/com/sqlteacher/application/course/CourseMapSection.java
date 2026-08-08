package com.sqlteacher.application.course;

import java.util.List;
import java.util.Objects;

public record CourseMapSection(String id, String title, int sortOrder, List<CourseMapActivity> activities) {
    public CourseMapSection {
        id = required(id, "id");
        title = required(title, "title");
        if (sortOrder < 0) throw new IllegalArgumentException("sortOrder must not be negative");
        activities = List.copyOf(Objects.requireNonNull(activities, "activities must not be null"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
