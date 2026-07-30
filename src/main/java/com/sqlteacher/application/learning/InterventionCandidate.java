package com.sqlteacher.application.learning;

import java.time.Instant;
import java.util.Objects;

public record InterventionCandidate(
    String id,
    String classroomId,
    String classroomName,
    String assignmentId,
    String assignmentTitle,
    String studentUserId,
    String studentDisplayName,
    DiagnosisReasonCode reason,
    String evidenceSummary,
    int priority,
    InterventionStatus status,
    Instant updatedAt
) {
    public InterventionCandidate {
        id = required(id, "id");
        classroomId = required(classroomId, "classroomId");
        classroomName = required(classroomName, "classroomName");
        assignmentId = required(assignmentId, "assignmentId");
        assignmentTitle = required(assignmentTitle, "assignmentTitle");
        studentUserId = required(studentUserId, "studentUserId");
        studentDisplayName = required(studentDisplayName, "studentDisplayName");
        Objects.requireNonNull(reason, "reason must not be null");
        evidenceSummary = required(evidenceSummary, "evidenceSummary");
        if (priority < 1 || priority > 100) throw new IllegalArgumentException("priority must be between 1 and 100");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
