package com.sqlteacher.application.collaboration;

import java.util.Objects;

public record AssignmentDeliveryResult(String operationId, Status status, int attemptNumber) {
    public AssignmentDeliveryResult {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (attemptNumber < 0) throw new IllegalArgumentException("attemptNumber must not be negative");
    }

    public enum Status {
        QUEUED,
        SUBMITTED,
        PASSED,
        REJECTED
    }
}
