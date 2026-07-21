package com.sqlteacher.application.exercise;

import java.time.Duration;

public record ExerciseProgressOverview(
    int sessions,
    int attempts,
    int submissions,
    int passedSubmissions,
    double submissionPassRate,
    Duration averageSubmissionDuration,
    int hintsUsed,
    int completedExercises
) {
    public ExerciseProgressOverview {
        if (sessions < 0 || attempts < 0 || submissions < 0 || passedSubmissions < 0
                || hintsUsed < 0 || completedExercises < 0) {
            throw new IllegalArgumentException("progress counts must not be negative");
        }
        if (passedSubmissions > submissions || submissionPassRate < 0 || submissionPassRate > 1) {
            throw new IllegalArgumentException("invalid submission pass rate");
        }
        if (averageSubmissionDuration == null || averageSubmissionDuration.isNegative()) {
            throw new IllegalArgumentException("averageSubmissionDuration must not be negative");
        }
    }
}
