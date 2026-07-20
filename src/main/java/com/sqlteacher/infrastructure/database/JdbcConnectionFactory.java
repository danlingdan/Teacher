package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

public final class JdbcConnectionFactory {
    private final DatabaseConfiguration configuration;

    public JdbcConnectionFactory(DatabaseConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
    }

    /**
     * 根据connectionId打开数据库连接
     * 当前支持：
     * app  -> app.db
     * demo -> demo.db
     */
    public Connection open(String connectionId) throws SQLException {

        Objects.requireNonNull(connectionId);
        SqliteDriver.ensureLoaded();

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

    public Connection open(
            DatabaseConnectionProfile profile,
            char[] password,
            Duration timeout) throws SQLException {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (!profile.enabled()) {
            throw new IllegalArgumentException("Disabled connection profile cannot be opened");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Connection connection;
        if (profile.target() instanceof SqliteConnectionTarget target) {
            SqliteDriver.ensureLoaded();
            SQLiteConfig sqliteConfig = new SQLiteConfig();
            sqliteConfig.setReadOnly(profile.readOnly());
            sqliteConfig.setBusyTimeout(toTimeoutMillis(timeout));
            connection = DriverManager.getConnection(
                "jdbc:sqlite:" + target.databasePath(),
                sqliteConfig.toProperties()
            );
        } else if (profile.target() instanceof ServerConnectionTarget target) {
            MysqlDataSource dataSource = new MysqlDataSource();
            dataSource.setServerName(target.host());
            dataSource.setPort(target.port());
            dataSource.setDatabaseName(target.databaseName());
            dataSource.setUser(target.username());
            dataSource.setPassword(new String(password));
            dataSource.setConnectTimeout(toTimeoutMillis(timeout));
            dataSource.setSocketTimeout(toTimeoutMillis(timeout));
            connection = dataSource.getConnection();
            connection.setReadOnly(profile.readOnly());
        } else {
            throw new IllegalArgumentException("Unsupported database connection target");
        }
        return connection;
    }

    private static int toTimeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
        return (int) millis;
    }
}
