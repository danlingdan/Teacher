package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record AssignmentAnalyticsRow(String userId, String email, String displayName,
                                     AssignmentStudentStatus status, int attemptCount, int passedAttempts,
                                     Instant lastSubmittedAt) { }
