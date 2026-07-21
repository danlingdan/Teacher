package com.sqlteacher.application.analytics;

public record KnowledgePointAnalytics(
    String knowledgePoint,
    int attempts,
    int failedSubmissions,
    int completedExercises,
    int totalExercises,
    double weaknessRate
) {
    public KnowledgePointAnalytics {
        if (knowledgePoint == null || knowledgePoint.isBlank() || attempts < 0 || failedSubmissions < 0
            || completedExercises < 0 || totalExercises < 0 || weaknessRate < 0 || weaknessRate > 1) {
            throw new IllegalArgumentException("knowledge-point analytics values are invalid");
        }
    }
}
