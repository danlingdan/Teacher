package com.sqlteacher.domain.course;

import java.util.List;
import java.util.Objects;

public record CourseDefinition(
    String id,
    String version,
    String title,
    String language,
    String license,
    String maintainer,
    List<String> outcomeIds
) {
    public CourseDefinition {
        id = required(id, "id");
        version = required(version, "version");
        title = required(title, "title");
        language = required(language, "language");
        license = required(license, "license");
        maintainer = required(maintainer, "maintainer");
        outcomeIds = List.copyOf(Objects.requireNonNull(outcomeIds, "outcomeIds must not be null"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
