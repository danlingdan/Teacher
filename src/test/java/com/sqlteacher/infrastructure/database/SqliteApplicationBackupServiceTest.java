package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.maintenance.BackupSnapshot;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteApplicationBackupServiceTest {
    @TempDir Path tempDirectory;

    @Test
    void shouldCreateListAndRestoreConsistentBackup() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        Path appDatabase = configuration.database().appDatabasePath();
        execute(appDatabase, "insert into app_event(event_type, message) values ('BEFORE', 'keep me')");
        SqliteApplicationBackupService service = new SqliteApplicationBackupService(configuration);

        BackupSnapshot backup = service.createBackup();
        execute(appDatabase, "delete from app_event");
        service.restoreBackup(backup.id());

        assertEquals(1, count(appDatabase, "select count(*) from app_event where message = 'keep me'"));
        List<BackupSnapshot> snapshots = service.listBackups();
        assertTrue(snapshots.stream().anyMatch(item -> item.id().equals(backup.id()) && !item.automatic()));
        assertTrue(snapshots.stream().anyMatch(BackupSnapshot::automatic));
    }

    @Test
    void shouldRestoreDemoDatabaseWithoutChangingApplicationData() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        execute(configuration.database().appDatabasePath(),
            "insert into app_event(event_type, message) values ('KEEP', 'application')");
        execute(configuration.database().demoDatabasePath(),
            "insert into student(id, name, score) values (3, 'Changed', 1)");

        new SqliteApplicationBackupService(configuration).restoreDemoDatabase();

        assertEquals(2, count(configuration.database().demoDatabasePath(), "select count(*) from student"));
        assertEquals(1, count(configuration.database().appDatabasePath(), "select count(*) from app_event"));
    }

    @Test
    void shouldRejectPathTraversalBackupIdentifier() {
        SqliteApplicationBackupService service = new SqliteApplicationBackupService(configuration());

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> service.restoreBackup("../app")
        );

        assertEquals("BACKUP_ID_INVALID", error.errorCode());
        assertFalse(service.listBackups().stream().anyMatch(item -> item.id().contains("..")));
    }

    private SqlTeacherConfiguration configuration() {
        return new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDirectory,
            new DatabaseConfiguration(tempDirectory.resolve("app.db"), tempDirectory.resolve("demo.db")),
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test-model"
            )
        );
    }

    private static void execute(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
