package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseCatalog;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.FileDatabaseConnectionTarget;
import com.sqlteacher.application.connection.GenericJdbcConnectionTarget;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.config.DatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.4.4：连接表单「列出数据库」服务——文件型/通用型直接给出提示，服务器失败降级不抛错。 */
class JdbcDatabaseCatalogServiceTest {

    @TempDir
    Path tempDir;

    private JdbcDatabaseCatalogService service;

    @BeforeEach
    void createService() {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDir.resolve("app.db"),
            tempDir.resolve("demo.db")
        );
        service = new JdbcDatabaseCatalogService(new JdbcConnectionFactory(configuration), Duration.ofSeconds(1));
    }

    @Test
    void fileBasedDialectsExplainThatNoPickerIsNeeded() {
        var sqlite = profile(new SqliteConnectionTarget(tempDir.resolve("a.db")));
        var duckdb = profile(new FileDatabaseConnectionTarget(DatabaseDialect.DUCKDB, tempDir.resolve("b.duckdb")));

        DatabaseCatalog sqliteCatalog = service.listDatabases(sqlite, new char[0]);
        DatabaseCatalog duckdbCatalog = service.listDatabases(duckdb, new char[0]);

        assertTrue(sqliteCatalog.databases().isEmpty());
        assertTrue(sqliteCatalog.message().contains("文件为库"));
        assertTrue(duckdbCatalog.databases().isEmpty());
        assertTrue(duckdbCatalog.message().contains("文件为库"));
    }

    @Test
    void genericJdbcExplainesThatTheUrlCarriesTheDatabase() {
        var generic = profile(new GenericJdbcConnectionTarget(
            "jdbc:example://host/db", "com.example.Driver", tempDir.resolve("driver.jar"), "user"));

        DatabaseCatalog catalog = service.listDatabases(generic, new char[0]);

        assertTrue(catalog.databases().isEmpty());
        assertTrue(catalog.message().contains("URL"));
    }

    @Test
    void unreachableServerDegradesToAManualInputHintInsteadOfThrowing() {
        // 端口 1 上没有 MySQL：打开连接必然失败，服务必须降级为提示而不是把异常抛给表单。
        var unreachable = profile(new ServerConnectionTarget(
            DatabaseDialect.MYSQL, "127.0.0.1", 1, "mysql", "root"));

        DatabaseCatalog catalog = service.listDatabases(unreachable, "secret".toCharArray());

        assertTrue(catalog.databases().isEmpty());
        assertTrue(catalog.message().contains("密码"));
    }

    @Test
    void everyServerDialectHasACatalogQueryAndFileDialectsDoNot() {
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            if (dialect.fileBased() || dialect.generic()) {
                assertEquals(null, JdbcDatabaseCatalogService.catalogQuery(dialect), dialect.name());
            } else {
                assertTrue(
                    JdbcDatabaseCatalogService.catalogQuery(dialect) != null
                        && !JdbcDatabaseCatalogService.catalogQuery(dialect).isBlank(),
                    dialect.name());
            }
        }
    }

    @Test
    void systemDatabasesAreFilteredCaseInsensitively() {
        List<String> filtered = JdbcDatabaseCatalogService.filterSystemDatabases(
            DatabaseDialect.MYSQL,
            List.of("INFORMATION_SCHEMA", "mysql", "performance_schema", "sys", "school", "course"));

        assertEquals(List.of("school", "course"), filtered);
    }

    private DatabaseConnectionProfile profile(com.sqlteacher.application.connection.DatabaseConnectionTarget target) {
        return new DatabaseConnectionProfile("catalog-test", "目录测试", target, false, true, false);
    }
}
