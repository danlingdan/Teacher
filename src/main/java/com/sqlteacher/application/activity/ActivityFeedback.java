package com.sqlteacher.application.activity;

import java.time.Instant;
import java.util.Objects;

public record ActivityFeedback(String id, String activityId, String authorId, String comment, Instant createdAt) {
    public ActivityFeedback {
        id = required(id, "id"); activityId = required(activityId, "activityId");
        authorId = required(authorId, "authorId"); comment = required(comment, "comment");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
