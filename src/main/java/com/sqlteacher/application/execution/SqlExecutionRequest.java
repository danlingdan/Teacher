package com.sqlteacher.application.execution;

import java.time.Duration;

public record SqlExecutionRequest(
    String connectionId,
    String sql,
    int maxRows,
    Duration timeout,
    boolean riskConfirmed
) {
    public SqlExecutionRequest(String connectionId, String sql, int maxRows, Duration timeout) {
        this(connectionId, sql, maxRows, timeout, false);
    }
}
