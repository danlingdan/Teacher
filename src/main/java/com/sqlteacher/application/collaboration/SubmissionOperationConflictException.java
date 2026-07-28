package com.sqlteacher.application.collaboration;

/** Raised when one idempotency key is reused for a different assignment. */
public final class SubmissionOperationConflictException extends RuntimeException {
    public SubmissionOperationConflictException() {
        super("operationId was already used for a different assignment");
    }
}
