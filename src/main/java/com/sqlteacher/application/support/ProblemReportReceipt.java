package com.sqlteacher.application.support;

import java.time.Instant;

public record ProblemReportReceipt(String reportId, String queryToken, Status status, Instant submittedAt) {
    public enum Status { RECEIVED, TRIAGED, IN_PROGRESS, RESOLVED, CLOSED, WITHDRAWN }
}
