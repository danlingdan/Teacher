package com.sqlteacher.application.collaboration;

import java.time.Instant;

/** Deterministic result plus an optional truncated submission payload (v3.7.0 TFB-S3:
 * the student's SQL text and score, allowlisted by the client, bounded to 16 KiB). */
public record AssignmentSubmissionRequest(String operationId, boolean passed, String resultHash, String errorCode,
                                          Instant clientCompletedAt, String submissionPayload) {
    public AssignmentSubmissionRequest(String operationId, boolean passed, String resultHash, String errorCode,
                                       Instant clientCompletedAt) {
        this(operationId, passed, resultHash, errorCode, clientCompletedAt, null);
    }

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
        submissionPayload = submissionPayload == null || submissionPayload.isBlank() ? null : submissionPayload;
        if (submissionPayload != null
            && submissionPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384) {
            throw new IllegalArgumentException("submissionPayload must be at most 16384 bytes");
        }
    }
}
