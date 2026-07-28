package com.sqlteacher.application.collaboration;

import java.time.Instant;

public interface AssignmentDeliveryService {
    AssignmentDeliveryResult deliver(String classroomId, String assignmentId, boolean passed,
                                      String errorCode, Instant completedAt);

    RetrySummary retryPending();

    int pendingCount();

    record RetrySummary(int attempted, int delivered, int rejected, int remaining) { }
}
