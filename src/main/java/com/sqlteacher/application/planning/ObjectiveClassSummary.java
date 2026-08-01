package com.sqlteacher.application.planning;

import java.time.Instant;

public record ObjectiveClassSummary(String classroomId, String objectiveId, String objectiveTitle,
                                    int totalStudents, int unknown, int needsSupport, int developing,
                                    int mastered, Instant generatedAt) {
    public ObjectiveClassSummary {
        if (classroomId == null || classroomId.isBlank() || objectiveId == null || objectiveId.isBlank()
            || objectiveTitle == null || objectiveTitle.isBlank() || totalStudents < 0 || unknown < 0
            || needsSupport < 0 || developing < 0 || mastered < 0 || generatedAt == null
            || unknown + needsSupport + developing + mastered != totalStudents) {
            throw new IllegalArgumentException("Objective class summary fields are invalid");
        }
    }
}
