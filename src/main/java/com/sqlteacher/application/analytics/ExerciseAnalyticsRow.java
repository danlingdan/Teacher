package com.sqlteacher.application.analytics;

import java.time.Instant;
import java.util.Optional;

public record ExerciseAnalyticsRow(
    String exerciseId,
    String title,
    String knowledgePoint,
    int attempts,
    int submissions,
    int passedSubmissions,
    int failedSubmissions,
    double passRate,
    boolean completed,
    Instant lastAttemptAt
) {
    public ExerciseAnalyticsRow {
        if (exerciseId == null || exerciseId.isBlank() || title == null || title.isBlank()
            || knowledgePoint == null || knowledgePoint.isBlank()) {
            throw new IllegalArgumentException("exercise analytics identity must not be blank");
        }
        if (attempts < 0 || submissions < 0 || passedSubmissions < 0 || failedSubmissions < 0
            || passRate < 0 || passRate > 1) {
            throw new IllegalArgumentException("exercise analytics values are invalid");
        }
    }

    public Optional<Instant> lastAttempt() {
        return Optional.ofNullable(lastAttemptAt);
    }
}
