package com.sqlteacher.application.planning;

import java.time.Instant;

public record PlanningHealthSummary(int activeObjectives, int pendingPlanOperations,
                                    int confirmedInterventions, int tutorFeedbackRows,
                                    Instant generatedAt) {
    public PlanningHealthSummary {
        if (activeObjectives < 0 || pendingPlanOperations < 0 || confirmedInterventions < 0
            || tutorFeedbackRows < 0 || generatedAt == null) {
            throw new IllegalArgumentException("Planning health values are invalid");
        }
    }
}
