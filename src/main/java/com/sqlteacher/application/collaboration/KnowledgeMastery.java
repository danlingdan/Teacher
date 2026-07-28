package com.sqlteacher.application.collaboration;

import java.util.List;

public record KnowledgeMastery(String knowledgePointId, String knowledgePointName, int attempts, int passes,
                               int masteryPercent, List<ExerciseRecommendation> recommendations) {
    public KnowledgeMastery {
        if (knowledgePointId == null || knowledgePointId.isBlank() || knowledgePointName == null
            || knowledgePointName.isBlank() || attempts < 0 || passes < 0 || passes > attempts
            || masteryPercent < 0 || masteryPercent > 100) {
            throw new IllegalArgumentException("Knowledge mastery fields are invalid");
        }
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
