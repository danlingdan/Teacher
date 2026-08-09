package com.sqlteacher.domain.activity;

import java.util.Objects;

public record ProjectMilestone(String id, String title, String acceptanceCriterion) {
    public ProjectMilestone {
        id = required(id, "id");
        title = required(title, "title");
        acceptanceCriterion = required(acceptanceCriterion, "acceptanceCriterion");
        if (id.length() > 64 || title.length() > 160 || acceptanceCriterion.length() > 500) {
            throw new IllegalArgumentException("project milestone is too long");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
