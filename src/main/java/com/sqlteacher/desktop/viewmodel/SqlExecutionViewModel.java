package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ViewModel for the SQL practice page.
 *
 * <p>Adapts the repackaged {@link SqlExecutionResult} into a UI-render-friendly shape: the raw
 * {@code List<Map<String, Object>>} rows are flattened into ordered display strings
 * ({@link SqlResultRowViewModel}) and the boolean success flag is translated into a
 * {@link UiStatusLevel}. The new backend metadata is surfaced as UI-friendly primitives:
 * {@code affectedRows} for non-query statements, {@code truncated} so the UI can warn that the
 * result set was capped, and {@code executionMillis} derived from the backend {@code Duration}.
 * The ViewModel does not retain the backend DTO, {@code Duration}, or JDBC types.
 */
public record SqlExecutionViewModel(
    String connectionId,
    String executedSql,
    boolean success,
    UiStatusLevel statusLevel,
    List<String> columns,
    List<SqlResultRowViewModel> rows,
    int rowCount,
    int affectedRows,
    boolean truncated,
    long executionMillis,
    String message
) {
    public SqlExecutionViewModel {
        connectionId = connectionId == null || connectionId.isBlank() ? DesktopConnections.DEMO : connectionId;
        executedSql = executedSql == null ? "" : executedSql;
        statusLevel = statusLevel == null ? UiStatusLevel.UNKNOWN : statusLevel;
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        message = message == null ? "" : message;
    }

    public static SqlExecutionViewModel from(SqlExecutionRequest request, SqlExecutionResult result) {
        List<String> columns = result.columns() == null ? List.of() : List.copyOf(result.columns());
        List<SqlResultRowViewModel> rows = toRows(columns, result.rows());
        long executionMillis = result.duration() == null ? 0L : result.duration().toMillis();
        return new SqlExecutionViewModel(
            request == null ? DesktopConnections.DEMO : request.connectionId(),
            request == null ? "" : request.sql(),
            result.success(),
            UiStatusLevel.fromSuccessFlag(result.success()),
            columns,
            rows,
            rows.size(),
            result.affectedRows(),
            result.truncated(),
            executionMillis,
            result.message()
        );
    }

    private static List<SqlResultRowViewModel> toRows(List<String> columns, List<Map<String, Object>> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) {
            return List.of();
        }
        List<SqlResultRowViewModel> rows = new ArrayList<>(rawRows.size());
        for (Map<String, Object> raw : rawRows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (String column : columns) {
                Object value = raw == null ? null : raw.get(column);
                cells.add(value == null ? "NULL" : value.toString());
            }
            rows.add(new SqlResultRowViewModel(cells));
        }
        return rows;
    }
}
