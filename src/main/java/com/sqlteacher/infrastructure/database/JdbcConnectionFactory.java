package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcConnectionFactory {
    private final DatabaseConfiguration configuration;

    public JdbcConnectionFactory(DatabaseConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
    }

    /**
     * 根据connectionId打开数据库连接
     *
     * 当前支持：
     * app  -> app.db
     * demo -> demo.db
     */
    public Connection open(String connectionId) throws SQLException {

        Objects.requireNonNull(connectionId);

        String url = switch (connectionId.toLowerCase()) {

            case "app" ->
                    "jdbc:sqlite:" + configuration.appDatabasePath();

            case "demo" ->
                    "jdbc:sqlite:" + configuration.demoDatabasePath();

            default ->
                    throw new IllegalArgumentException(
                            "Unknown connectionId: " + connectionId);
        };

        return DriverManager.getConnection(url);
    }
}
