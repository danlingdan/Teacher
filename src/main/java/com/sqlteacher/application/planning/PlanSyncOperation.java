package com.sqlteacher.application.planning;

public record PlanSyncOperation(String operationId, String courseId, String actionId,
                                StudyPlanActionState state, long expectedVersion, int attemptCount) {
    public PlanSyncOperation {
        if (operationId == null || operationId.isBlank() || courseId == null || courseId.isBlank()
            || actionId == null || actionId.isBlank() || state == null || expectedVersion < 0 || attemptCount < 0) {
            throw new IllegalArgumentException("Plan sync operation fields are invalid");
        }
    }
}
