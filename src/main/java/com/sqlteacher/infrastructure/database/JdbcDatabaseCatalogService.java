package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseCatalog;
import com.sqlteacher.application.connection.DatabaseCatalogService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * v3.4.4：连接表单的「列出数据库」后端。按方言执行目录查询，失败或方言不支持时返回
 * 空列表加提示，绝不阻塞表单手输；密码只用于打开瞬时连接，不记录、不返回。
 */
public final class JdbcDatabaseCatalogService implements DatabaseCatalogService {

    private static final Logger log = LoggerFactory.getLogger(JdbcDatabaseCatalogService.class);

    /** 各方言的系统库/模式，列出时过滤，避免用户连到系统库。 */
    private static final Set<String> SYSTEM_DATABASES = Set.of(
        "information_schema", "mysql", "performance_schema", "sys",
        "master", "tempdb", "model", "msdb",
        "sysibm", "syscat", "sysstat", "sysibmadm", "systools"
    );

    private final JdbcConnectionFactory connectionFactory;
    private final Duration timeout;

    public JdbcDatabaseCatalogService(JdbcConnectionFactory connectionFactory, Duration timeout) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public DatabaseCatalog listDatabases(DatabaseConnectionProfile profile, char[] password) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(password, "password must not be null");
        DatabaseDialect dialect = profile.dialect();
        if (dialect.fileBased()) {
            return new DatabaseCatalog(List.of(), "该类型以文件为库，无需选择数据库。");
        }
        if (dialect.generic()) {
            return new DatabaseCatalog(List.of(), "通用 JDBC 连接请在 URL 中指定数据库。");
        }
        String query = catalogQuery(dialect);
        if (query == null) {
            return new DatabaseCatalog(List.of(), "该数据库类型暂不支持自动列出，请手动输入。");
        }
        List<String> databases = new ArrayList<>();
        try (Connection connection = connectionFactory.open(profile, password, timeout);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                String name = resultSet.getString(1);
                if (name != null && !name.isBlank()) {
                    databases.add(name.trim());
                }
            }
        } catch (Exception error) {
            log.warn("Database catalog listing failed, dialect={}, failureType={}",
                dialect, JdbcFailureClassifier.classify(error));
            return new DatabaseCatalog(List.of(),
                "无法读取数据库列表：请先在密码框输入密码（或点「仅测试」验证连接）并确认服务可达，再重试。");
        }
        return new DatabaseCatalog(filterSystemDatabases(dialect, databases), "");
    }

    /** 方言到目录查询的映射；不支持自动列出的方言返回 null。包内可见以便测试。 */
    static String catalogQuery(DatabaseDialect dialect) {
        return switch (dialect) {
            case MYSQL, MARIADB, TIDB, OCEANBASE -> "SHOW DATABASES";
            case POSTGRESQL, GAUSSDB ->
                "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate ORDER BY datname";
            case SQL_SERVER -> "SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name";
            case ORACLE, DAMENG -> "SELECT username FROM all_users ORDER BY 1";
            case DB2 -> "SELECT schemaname FROM syscat.schemata ORDER BY schemaname";
            default -> null;
        };
    }

    /** 过滤系统库；大小写不敏感。包内可见以便测试。 */
    static List<String> filterSystemDatabases(DatabaseDialect dialect, List<String> databases) {
        return databases.stream()
            .filter(name -> !SYSTEM_DATABASES.contains(name.toLowerCase(Locale.ROOT)))
            .toList();
    }
}
