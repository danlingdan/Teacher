package com.sqlteacher.domain.course;

import java.util.List;
import java.util.Objects;

public record KnowledgePointDefinition(
    String id,
    String courseId,
    String name,
    List<String> aliases,
    List<String> cs2023Mappings
) {
    public KnowledgePointDefinition {
        id = required(id, "id");
        courseId = required(courseId, "courseId");
        name = required(name, "name");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases must not be null"));
        cs2023Mappings = List.copyOf(Objects.requireNonNull(cs2023Mappings, "cs2023Mappings must not be null"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
