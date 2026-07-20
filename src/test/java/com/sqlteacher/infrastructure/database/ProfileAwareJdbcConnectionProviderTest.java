package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileAwareJdbcConnectionProviderTest {
    @TempDir
    Path tempDir;

    private JdbcConnectionManagementService managementService;
    private InMemoryDatabaseCredentialSession credentialSession;
    private ProfileAwareJdbcConnectionProvider provider;

    @BeforeEach
    void setUp() {
        DatabaseConfiguration databaseConfiguration = new DatabaseConfiguration(
            tempDir.resolve("app.db"),
            tempDir.resolve("demo.db")
        );
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            databaseConfiguration,
            new AiConfiguration(
                URI.create("http://localhost:11434"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                "test-model"
            )
        );
        new SqliteAppDatabaseInitializer(configuration).initialize();
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(databaseConfiguration);
        managementService = new JdbcConnectionManagementService(connectionFactory, databaseConfiguration);
        credentialSession = new InMemoryDatabaseCredentialSession();
        provider = new ProfileAwareJdbcConnectionProvider(
            connectionFactory,
            managementService,
            credentialSession
        );
    }

    @Test
    void shouldOpenPersistedSqliteProfile() throws Exception {
        try (Connection connection = provider.open("demo", Duration.ofSeconds(2))) {
            assertEquals("SQLite", connection.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void shouldRejectServerProfileBeforeNetworkAccessWhenCredentialsAreMissing() {
        managementService.saveProfile(serverProfile(true));

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> provider.open("course.mysql", Duration.ofSeconds(2))
        );

        assertEquals("DATABASE_CREDENTIALS_REQUIRED", error.errorCode());
    }

    @Test
    void shouldRejectWritableServerProfileBeforeUsingCredentials() {
        managementService.saveProfile(serverProfile(false));
        credentialSession.remember("course.mysql", "unused".toCharArray());

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> provider.open("course.mysql", Duration.ofSeconds(2))
        );

        assertEquals("EXTERNAL_CONNECTION_READ_ONLY_REQUIRED", error.errorCode());
    }

    private static DatabaseConnectionProfile serverProfile(boolean readOnly) {
        return new DatabaseConnectionProfile(
            "course.mysql",
            "Course MySQL",
            new ServerConnectionTarget(DatabaseDialect.MYSQL, "localhost", 3306, "course", "teacher"),
            readOnly,
            true,
            false
        );
    }
}
