package com.sqlteacher.application.collaboration;

/** Raised when a task update uses a version older than the current server snapshot. */
public final class AssignmentVersionConflictException extends RuntimeException {
    private final ClassAssignment latest;

    public AssignmentVersionConflictException(ClassAssignment latest) {
        super("Assignment version is stale; refresh and retry with version " + latest.version());
        this.latest = latest;
    }

    public ClassAssignment latest() {
        return latest;
    }
}
