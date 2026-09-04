package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.FileDatabaseConnectionTarget;
import com.sqlteacher.application.connection.GenericJdbcConnectionTarget;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.mariadb.jdbc.Configuration;
import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

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

        // app/demo 库会被多个服务线程并发读写：没有 busy_timeout 时 SQLite 默认
        // 立即抛 database is locked；WAL 让读写不互斥，显著降低并发冲突。
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5_000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        return DriverManager.getConnection(url, sqliteConfig.toProperties());
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
        } else if (profile.target() instanceof FileDatabaseConnectionTarget target) {
            connection = openFileDatabase(target, profile.readOnly());
        } else if (profile.target() instanceof ServerConnectionTarget target) {
            connection = switch (target.dialect()) {
                case MYSQL, TIDB, OCEANBASE -> mysqlDataSource(target, password, timeout).getConnection();
                case MARIADB -> org.mariadb.jdbc.Driver.connect(
                    mariaDbConfiguration(target, password, timeout)
                );
                case POSTGRESQL, GAUSSDB, SQL_SERVER, ORACLE, DB2, DAMENG ->
                    openServerDatabase(target, password, timeout);
                default -> throw new IllegalArgumentException("Unsupported server database dialect: " + target.dialect());
            };
            connection.setReadOnly(profile.readOnly());
        } else if (profile.target() instanceof GenericJdbcConnectionTarget target) {
            connection = DynamicJdbcConnection.open(target, password);
            connection.setReadOnly(profile.readOnly());
        } else {
            throw new IllegalArgumentException("Unsupported database connection target");
        }
        return connection;
    }

    static MysqlDataSource mysqlDataSource(
            ServerConnectionTarget target,
            char[] password,
            Duration timeout
    ) throws SQLException {
        if (target.dialect().family() != DatabaseDialect.Family.MYSQL
                || target.dialect() == DatabaseDialect.MARIADB) {
            throw new IllegalArgumentException("Expected a MySQL-protocol server target");
        }
        int timeoutMillis = toTimeoutMillis(timeout);
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setServerName(target.host());
        dataSource.setPort(target.port());
        dataSource.setDatabaseName(target.databaseName());
        dataSource.setUser(target.username());
        dataSource.setPassword(new String(password));
        dataSource.setConnectTimeout(timeoutMillis);
        dataSource.setSocketTimeout(timeoutMillis);
        dataSource.setAllowMultiQueries(false);
        dataSource.setAllowLoadLocalInfile(false);
        return dataSource;
    }

    static Configuration mariaDbConfiguration(
            ServerConnectionTarget target,
            char[] password,
            Duration timeout
    ) {
        requireDialect(target, DatabaseDialect.MARIADB);
        int timeoutMillis = toTimeoutMillis(timeout);
        return new Configuration.Builder()
            .addHost(target.host(), target.port())
            .database(target.databaseName())
            .user(target.username())
            .password(new String(password))
            .connectTimeout(timeoutMillis)
            .socketTimeout(timeoutMillis)
            .allowMultiQueries(false)
            .allowLocalInfile(false)
            .dumpQueriesOnException(false)
            .build();
    }

    private static void requireDialect(ServerConnectionTarget target, DatabaseDialect expected) {
        Objects.requireNonNull(target, "target must not be null");
        if (target.dialect() != expected) {
            throw new IllegalArgumentException("Expected " + expected + " server target");
        }
    }

    private static int toTimeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
        return (int) millis;
    }

    private static Connection openFileDatabase(
            FileDatabaseConnectionTarget target,
            boolean readOnly) throws SQLException {
        Path absolutePath = target.databasePath().toAbsolutePath().normalize();
        Properties properties = new Properties();
        return switch (target.dialect()) {
            case DUCKDB -> {
                properties.setProperty("duckdb.read_only", Boolean.toString(readOnly));
                yield DriverManager.getConnection("jdbc:duckdb:" + absolutePath, properties);
            }
            case H2 -> {
                String suffix = readOnly ? ";ACCESS_MODE_DATA=r" : "";
                yield DriverManager.getConnection("jdbc:h2:file:" + absolutePath + suffix, properties);
            }
            default -> throw new IllegalArgumentException("Unsupported file database dialect: " + target.dialect());
        };
    }

    private static Connection openServerDatabase(
            ServerConnectionTarget target,
            char[] password,
            Duration timeout) throws SQLException {
        int timeoutMillis = toTimeoutMillis(timeout);
        int timeoutSeconds = Math.max(1, (int) Math.ceil(timeoutMillis / 1000.0));
        Properties properties = new Properties();
        properties.setProperty("user", target.username());
        properties.setProperty("password", new String(password));
        String url = switch (target.dialect()) {
            case POSTGRESQL, GAUSSDB -> {
                properties.setProperty("connectTimeout", Integer.toString(timeoutSeconds));
                properties.setProperty("socketTimeout", Integer.toString(timeoutSeconds));
                yield "jdbc:postgresql://" + hostForUrl(target.host()) + ":" + target.port()
                    + "/" + target.databaseName();
            }
            case SQL_SERVER -> {
                properties.setProperty("loginTimeout", Integer.toString(timeoutSeconds));
                properties.setProperty("socketTimeout", Integer.toString(timeoutMillis));
                properties.setProperty("encrypt", "true");
                properties.setProperty("trustServerCertificate", "false");
                yield "jdbc:sqlserver://" + hostForUrl(target.host()) + ":" + target.port()
                    + ";databaseName=" + target.databaseName();
            }
            case ORACLE -> {
                properties.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(timeoutMillis));
                properties.setProperty("oracle.jdbc.ReadTimeout", Integer.toString(timeoutMillis));
                yield "jdbc:oracle:thin:@//" + hostForUrl(target.host()) + ":" + target.port()
                    + "/" + target.databaseName();
            }
            case DB2 -> {
                properties.setProperty("loginTimeout", Integer.toString(timeoutSeconds));
                yield "jdbc:db2://" + hostForUrl(target.host()) + ":" + target.port()
                    + "/" + target.databaseName();
            }
            case DAMENG -> {
                properties.setProperty("connectTimeout", Integer.toString(timeoutMillis));
                yield "jdbc:dm://" + hostForUrl(target.host()) + ":" + target.port()
                    + "/" + target.databaseName();
            }
            default -> throw new IllegalArgumentException("Unsupported server database dialect: " + target.dialect());
        };
        return DriverManager.getConnection(url, properties);
    }

    private static String hostForUrl(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
