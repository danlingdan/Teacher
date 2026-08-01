package com.sqlteacher.application.planning;

import java.util.Objects;

public record StudyPlanAction(String id, String objectiveId, StudyPlanActionType type, String title,
                              String description, ObjectiveResourceType resourceType, String resourceId,
                              StudyPlanReasonCode reasonCode, int priority, StudyPlanActionState state,
                              String resolutionCondition, java.util.List<LearningEvidenceRef> evidence,
                              long stateVersion) {
    public StudyPlanAction(String id, String objectiveId, StudyPlanActionType type, String title,
                           String description, ObjectiveResourceType resourceType, String resourceId,
                           StudyPlanReasonCode reasonCode, int priority) {
        this(id, objectiveId, type, title, description, resourceType, resourceId, reasonCode, priority,
            StudyPlanActionState.OPEN, "完成关联学习资源或产生新的有效证据", java.util.List.of());
    }

    public StudyPlanAction(String id, String objectiveId, StudyPlanActionType type, String title,
                           String description, ObjectiveResourceType resourceType, String resourceId,
                           StudyPlanReasonCode reasonCode, int priority, StudyPlanActionState state,
                           String resolutionCondition, java.util.List<LearningEvidenceRef> evidence) {
        this(id, objectiveId, type, title, description, resourceType, resourceId, reasonCode, priority, state,
            resolutionCondition, evidence, 0);
    }

    public StudyPlanAction {
        id = required(id, "id");
        objectiveId = required(objectiveId, "objectiveId");
        Objects.requireNonNull(type, "type must not be null");
        title = required(title, "title");
        description = required(description, "description");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        resourceId = required(resourceId, "resourceId");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (priority < 1 || priority > 100) throw new IllegalArgumentException("priority must be between 1 and 100");
        state = state == null ? StudyPlanActionState.OPEN : state;
        resolutionCondition = required(resolutionCondition, "resolutionCondition");
        evidence = evidence == null ? java.util.List.of() : java.util.List.copyOf(evidence);
        if (stateVersion < 0) throw new IllegalArgumentException("stateVersion must not be negative");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
