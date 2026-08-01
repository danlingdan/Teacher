package com.sqlteacher.application.planning;

import com.sqlteacher.application.knowledge.GroundedKnowledgeAnswer;

public record GroundedTutorResult(String sessionId, String courseScope, String objectiveId,
                                  GroundedKnowledgeAnswer answer) {
    public GroundedTutorResult {
        if (sessionId == null || sessionId.isBlank() || courseScope == null || courseScope.isBlank()
            || objectiveId == null || objectiveId.isBlank() || answer == null) {
            throw new IllegalArgumentException("Grounded tutor result fields are invalid");
        }
    }
}
