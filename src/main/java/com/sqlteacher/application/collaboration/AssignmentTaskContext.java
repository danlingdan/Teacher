package com.sqlteacher.application.collaboration;

import java.util.Objects;

public record AssignmentTaskContext(String classroomId, ClassAssignment assignment) {
    public AssignmentTaskContext {
        if (classroomId == null || classroomId.isBlank()) {
            throw new IllegalArgumentException("classroomId must not be blank");
        }
        Objects.requireNonNull(assignment, "assignment must not be null");
    }
}
