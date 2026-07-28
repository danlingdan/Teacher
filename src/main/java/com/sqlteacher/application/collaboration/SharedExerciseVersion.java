package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;

public record SharedExerciseVersion(String id, String exerciseId, String courseId, int version, String title,
                                    String prompt, String datasetVersion, String evaluationRule,
                                    List<String> knowledgePointIds, String contentHash, ContentStatus status,
                                    String createdBy, Instant publishedAt) {
    public SharedExerciseVersion {
        if (id == null || id.isBlank() || exerciseId == null || exerciseId.isBlank()
            || courseId == null || courseId.isBlank() || title == null || title.isBlank()
            || prompt == null || prompt.isBlank() || datasetVersion == null || datasetVersion.isBlank()
            || evaluationRule == null || evaluationRule.isBlank() || contentHash == null || contentHash.isBlank()
            || createdBy == null || createdBy.isBlank() || publishedAt == null || version < 1) {
            throw new IllegalArgumentException("Shared exercise version fields are invalid");
        }
        knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
        status = status == null ? ContentStatus.ACTIVE : status;
    }
}
