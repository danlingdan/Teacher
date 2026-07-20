package com.sqlteacher.infrastructure.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

@FunctionalInterface
public interface JdbcConnectionProvider {
    Connection open(String connectionId, Duration timeout) throws SQLException;

    default boolean isReadOnly(String connectionId) {
        return false;
    }
}
