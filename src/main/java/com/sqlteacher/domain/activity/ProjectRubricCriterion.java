package com.sqlteacher.domain.activity;

import java.util.Objects;

public record ProjectRubricCriterion(String id, String title, int weight) {
    public ProjectRubricCriterion {
        id = required(id, "id");
        title = required(title, "title");
        if (id.length() > 64 || title.length() > 160 || weight < 1 || weight > 100) {
            throw new IllegalArgumentException("project rubric criterion is invalid");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
