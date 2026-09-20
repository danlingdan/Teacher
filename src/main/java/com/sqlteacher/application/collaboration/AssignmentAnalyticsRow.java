package com.sqlteacher.application.collaboration;

import java.time.Instant;

/**
 * Per-student task analytics row. {@code lastSubmissionPayload} (v3.7.0 TFB-S3) is the
 * allowlisted payload JSON of the student's latest submission — truncated SQL text and
 * score — and is null for students that never submitted.
 */
public record AssignmentAnalyticsRow(String userId, String email, String displayName,
                                     AssignmentStudentStatus status, int attemptCount, int passedAttempts,
                                     Instant lastSubmittedAt, String lastSubmissionPayload) { }
