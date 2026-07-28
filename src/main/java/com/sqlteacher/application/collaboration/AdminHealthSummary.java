package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record AdminHealthSummary(int activeUsers, int disabledUsers, int activeAccessSessions,
                                 int activeRefreshSessions, int assignments, int submissions,
                                 Instant generatedAt) { }
