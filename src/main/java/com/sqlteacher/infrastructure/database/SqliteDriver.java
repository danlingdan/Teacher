package com.sqlteacher.infrastructure.database;

import java.sql.SQLException;

public final class SqliteDriver {
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";

    private SqliteDriver() {
    }

    public static void ensureLoaded() throws SQLException {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException error) {
            throw new SQLException("SQLite JDBC driver is not available", error);
        }
    }
}
