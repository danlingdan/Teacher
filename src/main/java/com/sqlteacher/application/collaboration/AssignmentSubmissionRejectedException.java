package com.sqlteacher.application.collaboration;

/** Stable rejection raised when server-side assignment state does not allow a submission. */
public final class AssignmentSubmissionRejectedException extends RuntimeException {
    private final String code;

    public AssignmentSubmissionRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
