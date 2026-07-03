package com.sqlteacher.application.execution;

import java.util.List;
import java.util.Map;

public record SqlExecutionResult(
    boolean success,
    List<String> columns,
    List<Map<String, Object>> rows,
    String message
) {
}
