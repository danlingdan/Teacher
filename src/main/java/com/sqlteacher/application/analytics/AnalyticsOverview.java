package com.sqlteacher.application.analytics;

import java.time.Duration;

public record AnalyticsOverview(
    int sessions,
    int attempts,
    int submissions,
    int passedSubmissions,
    double passRate,
    double averageAttemptsPerCompletedExercise,
    Duration averageSubmissionDuration,
    int completedExercises,
    int totalExercises,
    double completionRate
) {
    public AnalyticsOverview {
        if (sessions < 0 || attempts < 0 || submissions < 0 || passedSubmissions < 0
            || completedExercises < 0 || totalExercises < 0) {
            throw new IllegalArgumentException("analytics counts must not be negative");
        }
        if (passRate < 0 || passRate > 1 || completionRate < 0 || completionRate > 1
            || averageAttemptsPerCompletedExercise < 0) {
            throw new IllegalArgumentException("analytics rates must be within their valid range");
        }
        if (averageSubmissionDuration == null || averageSubmissionDuration.isNegative()) {
            throw new IllegalArgumentException("averageSubmissionDuration must not be negative");
        }
    }
}
