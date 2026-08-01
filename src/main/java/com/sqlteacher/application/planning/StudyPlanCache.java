package com.sqlteacher.application.planning;

import java.util.List;

public interface StudyPlanCache {
    void saveObjectives(String courseId, List<CourseObjective> objectives);

    StudyPlanRefresh save(StudyPlanSnapshot snapshot);

    List<StudyPlanSnapshot> currentPlans();

    PlanSyncOperation updateAction(String courseId, String actionId, StudyPlanActionState state);

    int pendingOperations();

    List<PlanSyncOperation> pending();

    void markDelivered(String operationId, String actionId, long serverVersion);

    void markFailed(String operationId, String errorCode, boolean retryable);
}
