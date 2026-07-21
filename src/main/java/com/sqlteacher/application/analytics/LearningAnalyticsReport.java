package com.sqlteacher.application.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record LearningAnalyticsReport(
    AnalyticsFilter filter,
    Instant generatedAt,
    AnalyticsOverview overview,
    List<ExerciseAnalyticsRow> exercises,
    List<ErrorAnalytics> commonErrors,
    List<KnowledgePointAnalytics> knowledgePoints
) {
    public LearningAnalyticsReport {
        Objects.requireNonNull(filter);
        Objects.requireNonNull(generatedAt);
        Objects.requireNonNull(overview);
        exercises = List.copyOf(exercises);
        commonErrors = List.copyOf(commonErrors);
        knowledgePoints = List.copyOf(knowledgePoints);
    }
}
