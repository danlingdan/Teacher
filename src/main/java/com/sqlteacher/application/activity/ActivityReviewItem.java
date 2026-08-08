package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;

import java.time.Instant;
import java.util.Objects;

public record ActivityReviewItem(String evaluationId, String ownerId, String activityId, String title,
                                 ActivityType activityType, ActivityEvaluationStatus status,
                                 String reasonCode, String summary, Instant occurredAt) {
    public ActivityReviewItem {
        evaluationId = required(evaluationId, "evaluationId"); ownerId = required(ownerId, "ownerId");
        activityId = required(activityId, "activityId"); title = required(title, "title");
        activityType = Objects.requireNonNull(activityType); status = Objects.requireNonNull(status);
        reasonCode = reasonCode == null ? "" : reasonCode.trim(); summary = required(summary, "summary");
        occurredAt = Objects.requireNonNull(occurredAt);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
