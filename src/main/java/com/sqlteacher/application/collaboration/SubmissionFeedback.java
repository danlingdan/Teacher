package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;

public record SubmissionFeedback(String submissionId, String assignmentId, String studentUserId,
                                 FeedbackStatus status, String comment, List<String> knowledgePointIds,
                                 long version, String authorUserId, Instant updatedAt) {
    public SubmissionFeedback {
        if (submissionId == null || submissionId.isBlank() || assignmentId == null || assignmentId.isBlank()
            || studentUserId == null || studentUserId.isBlank() || status == null
            || authorUserId == null || authorUserId.isBlank() || updatedAt == null || version < 1) {
            throw new IllegalArgumentException("Submission feedback fields are invalid");
        }
        comment = comment == null ? "" : comment.trim();
        if (comment.length() > 2_000) throw new IllegalArgumentException("Feedback comment is too long");
        knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
    }
}
