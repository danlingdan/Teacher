package com.sqlteacher.application.execution;

import java.time.Instant;

/** One locally recorded SQL execution shown in the data workbench history. */
public record SqlHistoryEntry(
    String connectionId,
    String connectionName,
    String sqlText,
    boolean successful,
    int rowCount,
    long durationMillis,
    Instant createdAt
) {
    public SqlHistoryEntry {
        connectionId = requireText(connectionId, "connectionId");
        connectionName = connectionName == null ? "" : connectionName;
        sqlText = requireText(sqlText, "sqlText");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
