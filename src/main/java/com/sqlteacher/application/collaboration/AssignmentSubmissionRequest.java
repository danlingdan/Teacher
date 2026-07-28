package com.sqlteacher.application.collaboration;

import java.time.Instant;

/** Minimal deterministic result sent to the server; it intentionally contains no executable SQL. */
public record AssignmentSubmissionRequest(String operationId, boolean passed, String resultHash, String errorCode,
                                          Instant clientCompletedAt) {
    public AssignmentSubmissionRequest {
        if (operationId == null || !operationId.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("operationId must contain 8 to 128 safe characters");
        }
        if (resultHash == null || !resultHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("resultHash must be a SHA-256 hex value");
        }
        resultHash = resultHash.toLowerCase(java.util.Locale.ROOT);
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
        if (errorCode != null && !errorCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("errorCode must contain only A-Z, 0-9, or underscore");
        }
        if (passed) errorCode = null;
    }
}
