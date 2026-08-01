package com.sqlteacher.application.planning;

public record StudyPlanChange(String actionId, StudyPlanChangeType type, String explanation) {
    public StudyPlanChange {
        if (actionId == null || actionId.isBlank() || type == null || explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("Study plan change fields are invalid");
        }
        actionId = actionId.trim();
        explanation = explanation.trim();
    }
}
