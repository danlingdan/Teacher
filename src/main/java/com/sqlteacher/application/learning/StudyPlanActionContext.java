package com.sqlteacher.application.learning;

import com.sqlteacher.application.planning.ObjectiveResourceType;

public record StudyPlanActionContext(String courseId, String actionId, ObjectiveResourceType resourceType,
                                     String resourceId, long stateVersion) {
    public StudyPlanActionContext {
        if (courseId == null || courseId.isBlank() || actionId == null || actionId.isBlank()
            || resourceType == null || resourceId == null || resourceId.isBlank() || stateVersion < 0) {
            throw new IllegalArgumentException("Study plan action context is invalid");
        }
    }
}
