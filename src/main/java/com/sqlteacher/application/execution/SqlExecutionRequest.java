package com.sqlteacher.application.execution;

import java.time.Duration;

public record SqlExecutionRequest(
    String connectionId,
    String sql,
    int maxRows,
    Duration timeout
) {
}
