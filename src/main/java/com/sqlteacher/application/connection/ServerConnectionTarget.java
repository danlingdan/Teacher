package com.sqlteacher.application.connection;

import java.util.Objects;

public record ServerConnectionTarget(
    DatabaseDialect dialect,
    String host,
    int port,
    String databaseName,
    String username
) implements DatabaseConnectionTarget {
    public ServerConnectionTarget {
        Objects.requireNonNull(dialect, "dialect must not be null");
        if (dialect == DatabaseDialect.SQLITE) {
            throw new IllegalArgumentException("Server connection target requires MySQL or MariaDB dialect");
        }
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        databaseName = requireText(databaseName, "databaseName");
        username = requireText(username, "username");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
