package com.sqlteacher.application.execution;

import java.util.List;
import java.util.Map;
import java.time.Duration;

public record SqlExecutionResult(
    boolean success,
    List<String> columns,
    List<Map<String, Object>> rows,
    int affectedRows,
    boolean truncated,
    String message,
    Duration duration
) {
    public SqlExecutionResult {
        columns = List.copyOf(columns);
        rows = rows.stream().map(Map::copyOf).toList();
    }

    public SqlExecutionResult(
        boolean success,
        List<String> columns,
        List<Map<String, Object>> rows,
        String message
    ) {
        this(success, columns, rows, 0, false, message, Duration.ZERO);
    }
}
