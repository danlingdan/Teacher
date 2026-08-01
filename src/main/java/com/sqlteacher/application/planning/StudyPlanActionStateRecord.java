package com.sqlteacher.application.planning;

import java.time.Instant;

public record StudyPlanActionStateRecord(String ownerId, String courseId, String actionId,
                                         StudyPlanActionState state, long version, Instant updatedAt) {
    public StudyPlanActionStateRecord {
        if (ownerId == null || ownerId.isBlank() || courseId == null || courseId.isBlank()
            || actionId == null || actionId.isBlank() || state == null || version < 1 || updatedAt == null) {
            throw new IllegalArgumentException("Study plan action state fields are invalid");
        }
    }
}
