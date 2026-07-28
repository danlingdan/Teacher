package com.sqlteacher.application.collaboration;

public record ExerciseRecommendation(String exerciseVersionId, String title, String knowledgePointId,
                                     String reason, int priority) {
    public ExerciseRecommendation {
        if (exerciseVersionId == null || exerciseVersionId.isBlank() || title == null || title.isBlank()
            || knowledgePointId == null || knowledgePointId.isBlank() || reason == null || reason.isBlank()
            || priority < 1) {
            throw new IllegalArgumentException("Recommendation fields are invalid");
        }
    }
}
