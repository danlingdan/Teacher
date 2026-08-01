package com.sqlteacher.application.planning;

import java.time.Instant;

public record ObjectiveInterventionDraft(String id, String classroomId, String courseId, String objectiveId,
                                         String reasonCode, String action, int impactCount, long objectiveVersion,
                                         String confirmationToken, String status, String createdBy,
                                         Instant createdAt, Instant confirmedAt) {
    public ObjectiveInterventionDraft {
        if (id == null || id.isBlank() || classroomId == null || classroomId.isBlank()
            || courseId == null || courseId.isBlank() || objectiveId == null || objectiveId.isBlank()
            || reasonCode == null || reasonCode.isBlank() || action == null || action.isBlank()
            || impactCount < 0 || objectiveVersion < 1 || confirmationToken == null || status == null
            || status.isBlank() || createdBy == null || createdBy.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Objective intervention draft fields are invalid");
        }
    }
}
