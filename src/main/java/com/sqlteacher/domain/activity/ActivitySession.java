package com.sqlteacher.domain.activity;

import java.time.Instant;
import java.util.Objects;

public record ActivitySession(
    String id,
    String activityId,
    int activityVersion,
    ActivitySessionStatus status,
    Instant startedAt,
    Instant updatedAt
) {
    public ActivitySession {
        id = required(id, "id");
        activityId = required(activityId, "activityId");
        if (activityVersion < 1) throw new IllegalArgumentException("activityVersion must be positive");
        status = Objects.requireNonNull(status, "status must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(startedAt)) throw new IllegalArgumentException("updatedAt must not be before startedAt");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
