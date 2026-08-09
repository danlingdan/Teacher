package com.sqlteacher.application.activity;

import java.time.Instant;
import java.util.Objects;

public record ProjectPortfolioEntry(
    String activityId,
    String title,
    int submissionVersion,
    ActivityEvaluationStatus gateStatus,
    String reasonCode,
    String artifactSha256,
    Instant submittedAt,
    String reviewState
) {
    public ProjectPortfolioEntry {
        activityId = required(activityId, "activityId");
        title = required(title, "title");
        if (submissionVersion < 1) throw new IllegalArgumentException("submissionVersion must be positive");
        gateStatus = Objects.requireNonNull(gateStatus, "gateStatus must not be null");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        artifactSha256 = required(artifactSha256, "artifactSha256");
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        reviewState = required(reviewState, "reviewState");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
