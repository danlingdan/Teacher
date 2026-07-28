package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record AssignmentSubmission(String id, String operationId, String classroomId, String assignmentId,
                                   String userId, int attemptNumber, AssignmentSubmissionStatus status,
                                   String resultHash, String errorCode, Instant clientCompletedAt,
                                   Instant submittedAt) {
    public AssignmentSubmission {
        if (id == null || id.isBlank() || operationId == null || operationId.isBlank()
            || classroomId == null || classroomId.isBlank() || assignmentId == null || assignmentId.isBlank()
            || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Submission identity fields must not be blank");
        }
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        if (status == null || submittedAt == null) {
            throw new IllegalArgumentException("Submission status and submittedAt must not be null");
        }
    }
}
