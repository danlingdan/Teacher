package com.sqlteacher.application.connection;

import java.util.List;
import java.util.Objects;

/**
 * Databases enumerable on a server for the connection form. {@code databases} is empty when the
 * dialect cannot enumerate (file-based, generic JDBC, or enumeration failed); {@code message}
 * then carries a user-facing hint. Never contains credentials.
 */
public record DatabaseCatalog(List<String> databases, String message) {
    public DatabaseCatalog {
        Objects.requireNonNull(databases, "databases must not be null");
        databases = List.copyOf(databases);
        message = message == null ? "" : message;
    }
}
