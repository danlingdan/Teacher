package com.sqlteacher.infrastructure.database;

import java.sql.SQLException;

final class SqliteDriver {
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";

    private SqliteDriver() {
    }

    static void ensureLoaded() throws SQLException {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException error) {
            throw new SQLException("SQLite JDBC driver is not available", error);
        }
    }
}
