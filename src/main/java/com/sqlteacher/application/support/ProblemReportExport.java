package com.sqlteacher.application.support;

import java.time.Instant;
import java.util.List;

/**
 * Export of a user's own problem-report metadata. It deliberately excludes
 * internal audit data and other users' content.
 */
public record ProblemReportExport(String reportId, String type, String status, String summary,
                                  Instant submittedAt, Instant updatedAt, List<StatusChange> history) {
    public record StatusChange(String status, String reasonCode, Instant at) { }
}
