package com.sqlteacher.application.collaboration;

import java.time.Instant;

public interface AssignmentDeliveryService {
    AssignmentDeliveryResult deliver(String classroomId, String assignmentId, boolean passed,
                                      String errorCode, Instant completedAt);

    /** v3.7.0 TFB-S3: submissions may carry the truncated SQL and score for teacher review. */
    default AssignmentDeliveryResult deliver(String classroomId, String assignmentId, boolean passed,
                                              String errorCode, Instant completedAt, String sqlText, Integer score) {
        return deliver(classroomId, assignmentId, passed, errorCode, completedAt);
    }

    RetrySummary retryPending();

    int pendingCount();

    record RetrySummary(int attempted, int delivered, int rejected, int remaining) { }
}
