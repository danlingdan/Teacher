package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectionManagementServiceTest {
    @TempDir
    Path tempDir;

    private DatabaseConfiguration databaseConfiguration;
    private JdbcConnectionFactory connectionFactory;
    private JdbcConnectionManagementService service;

    @BeforeEach
    void setUp() {
        databaseConfiguration = new DatabaseConfiguration(
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
        connectionFactory = new JdbcConnectionFactory(databaseConfiguration);
        service = new JdbcConnectionManagementService(connectionFactory, databaseConfiguration);
    }

    @Test
    void shouldCreateBuiltInProfilesAndSelectDemoByDefault() {
        List<DatabaseConnectionProfile> profiles = service.listProfiles();

        assertEquals(List.of("demo", "app"), profiles.stream().map(DatabaseConnectionProfile::id).toList());
        assertTrue(profiles.stream().allMatch(DatabaseConnectionProfile::builtIn));
        assertEquals("demo", service.currentProfile().orElseThrow().id());
        assertThrows(UnsupportedOperationException.class, () -> profiles.add(profiles.getFirst()));
    }

    @Test
    void shouldPersistAndReloadAMysqlProfile() {
        DatabaseConnectionProfile profile = mysqlProfile(true);

        DatabaseConnectionProfile saved = service.saveProfile(profile);
        JdbcConnectionManagementService reloaded = new JdbcConnectionManagementService(
            connectionFactory,
            databaseConfiguration
        );

        assertEquals(profile, saved);
        assertEquals(profile, reloaded.findProfile(profile.id()).orElseThrow());
        assertEquals(3, reloaded.listProfiles().size());
    }

    @Test
    void shouldSelectAnEnabledProfileAndFallbackWhenItIsDisabled() {
        service.saveProfile(mysqlProfile(true));

        assertEquals("course.mysql", service.selectProfile("course.mysql").id());
        assertEquals("course.mysql", service.currentProfile().orElseThrow().id());

        service.saveProfile(mysqlProfile(false));

        assertEquals("demo", service.currentProfile().orElseThrow().id());
        assertThrows(IllegalArgumentException.class, () -> service.selectProfile("course.mysql"));
    }

    @Test
    void shouldFallbackToDemoWhenTheCurrentProfileIsRemoved() {
        service.saveProfile(mysqlProfile(true));
        service.selectProfile("course.mysql");

        service.removeProfile("course.mysql");

        assertFalse(service.findProfile("course.mysql").isPresent());
        assertEquals("demo", service.currentProfile().orElseThrow().id());
    }

    @Test
    void shouldProtectBuiltInProfiles() {
        DatabaseConnectionProfile replacement = new DatabaseConnectionProfile(
            "demo",
            "Replacement",
            new SqliteConnectionTarget(tempDir.resolve("other.db")),
            false,
            true,
            false
        );

        assertThrows(IllegalArgumentException.class, () -> service.saveProfile(replacement));
        assertThrows(IllegalArgumentException.class, () -> service.removeProfile("app"));
        assertThrows(IllegalArgumentException.class, () -> service.removeProfile("demo"));
        assertTrue(service.findProfile("demo").orElseThrow().builtIn());
    }

    private static DatabaseConnectionProfile mysqlProfile(boolean enabled) {
        return new DatabaseConnectionProfile(
            "course.mysql",
            "Course MySQL",
            new ServerConnectionTarget(
                DatabaseDialect.MYSQL,
                "localhost",
                3306,
                "course",
                "teacher"
            ),
            true,
            enabled,
            false
        );
    }
}
