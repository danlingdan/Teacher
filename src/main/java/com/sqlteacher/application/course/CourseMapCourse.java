package com.sqlteacher.application.course;

import java.util.List;
import java.util.Objects;

public record CourseMapCourse(String id, String title, String version, List<CourseMapSection> sections) {
    public CourseMapCourse {
        id = required(id, "id");
        title = required(title, "title");
        version = required(version, "version");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
