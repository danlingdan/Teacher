package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;

public record AssignmentContentSnapshot(String assignmentId, String exerciseVersionId, String title, String prompt,
                                        String datasetVersion, String evaluationRule,
                                        List<String> knowledgePointIds, String snapshotHash, Instant createdAt) {
    public AssignmentContentSnapshot {
        if (assignmentId == null || assignmentId.isBlank() || exerciseVersionId == null || exerciseVersionId.isBlank()
            || title == null || title.isBlank() || prompt == null || prompt.isBlank()
            || datasetVersion == null || datasetVersion.isBlank() || evaluationRule == null || evaluationRule.isBlank()
            || snapshotHash == null || snapshotHash.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Assignment snapshot fields are invalid");
        }
        knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
    }
}
