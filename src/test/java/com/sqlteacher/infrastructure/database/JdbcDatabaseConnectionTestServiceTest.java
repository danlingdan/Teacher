package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTestResult;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDatabaseConnectionTestServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldReturnStandardMetadataForAWorkingSqliteProfile() throws Exception {
        JdbcDatabaseConnectionTestService service = service(Duration.ofSeconds(1));
        Path working = tempDir.resolve("working.db");
        java.nio.file.Files.createFile(working);
        DatabaseConnectionProfile profile = profile(working, true);

        DatabaseConnectionTestResult result = service.testConnection(profile, new char[0]);

        assertTrue(result.successful());
        assertEquals("连接成功。", result.message());
        assertEquals("SQLite", result.databaseProduct());
        assertFalse(result.databaseVersion().isBlank());
        assertFalse(result.elapsed().isNegative());
    }

    @Test
    void shouldHintWhenSqliteDatabaseFileWillBeCreated() {
        JdbcDatabaseConnectionTestService service = service(Duration.ofSeconds(1));
        DatabaseConnectionProfile profile = profile(tempDir.resolve("brand-new.db"), true);

        DatabaseConnectionTestResult result = service.testConnection(profile, new char[0]);

        assertTrue(result.successful());
        assertTrue(result.message().startsWith("连接成功。"));
        assertTrue(result.message().contains("新的空数据库"));
        assertFalse(result.message().contains("brand-new"));
    }

    @Test
    void shouldReturnSafeFailureWithoutExposingTheDatabasePath() {
        JdbcDatabaseConnectionTestService service = service(Duration.ofSeconds(1));
        Path unavailablePath = tempDir.resolve("missing-parent").resolve("secret-name.db");
        DatabaseConnectionProfile profile = profile(unavailablePath, true);

        DatabaseConnectionTestResult result = service.testConnection(profile, new char[0]);

        assertFalse(result.successful());
        assertTrue(result.message().startsWith("连接失败，请检查数据库地址、凭据和服务状态。"));
        assertTrue(result.message().contains("SQLITE_CANTOPEN"));
        assertFalse(result.message().contains("secret-name"));
        assertFalse(result.message().contains(unavailablePath.toString()));
        assertEquals("", result.databaseProduct());
        assertEquals("", result.databaseVersion());
    }

    @Test
    void shouldRejectDisabledProfileWithoutOpeningAConnection() {
        JdbcDatabaseConnectionTestService service = service(Duration.ofSeconds(1));

        DatabaseConnectionTestResult result = service.testConnection(
            profile(tempDir.resolve("disabled.db"), false),
            new char[0]
        );

        assertFalse(result.successful());
        assertEquals("连接配置已禁用，请启用后重试。", result.message());
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("disabled.db")));
    }

    private JdbcDatabaseConnectionTestService service(Duration timeout) {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDir.resolve("app.db"),
            tempDir.resolve("demo.db")
        );
        return new JdbcDatabaseConnectionTestService(new JdbcConnectionFactory(configuration), timeout);
    }

    private static DatabaseConnectionProfile profile(Path path, boolean enabled) {
        return new DatabaseConnectionProfile(
            "test.sqlite",
            "Test SQLite",
            new SqliteConnectionTarget(path),
            false,
            enabled,
            false
        );
    }
}
