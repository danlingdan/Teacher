package com.sqlteacher.application.connection;

import java.nio.file.Path;
import java.util.Objects;

/** Advanced compatibility target for a user-selected JDBC 4.x driver. */
public record GenericJdbcConnectionTarget(
    String jdbcUrl,
    String driverClass,
    Path driverJar,
    String username
) implements DatabaseConnectionTarget {
    public GenericJdbcConnectionTarget {
        jdbcUrl = requirePrefix(jdbcUrl);
        driverClass = requireText(driverClass, "driverClass");
        Objects.requireNonNull(driverJar, "driverJar must not be null");
        driverJar = driverJar.normalize();
        username = username == null ? "" : username.trim();
    }

    @Override public DatabaseDialect dialect() { return DatabaseDialect.GENERIC; }

    private static String requirePrefix(String value) {
        String normalized = requireText(value, "jdbcUrl");
        if (!normalized.startsWith("jdbc:") || normalized.length() > 4096) {
            throw new IllegalArgumentException("jdbcUrl must be a JDBC URL with at most 4096 characters");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
