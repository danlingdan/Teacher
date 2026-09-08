package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.maintenance.BackupSnapshot;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            "insert into Student(Sno, Sname, Ssex, Sbirthdate, Smajor) values (20189999, 'Changed', '男', '2000-01-01', '软件工程')");

        new SqliteApplicationBackupService(configuration).restoreDemoDatabase();

        assertEquals(7, count(configuration.database().demoDatabasePath(), "select count(*) from Student"));
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

    @Test
    void shouldRemoveStaleWalSidecarsAndApplyRestoredDatabase() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        Path appDatabase = configuration.database().appDatabasePath();
        execute(appDatabase, "insert into app_event(event_type, message) values ('BEFORE', 'keep me')");
        SqliteApplicationBackupService service = new SqliteApplicationBackupService(configuration);
        BackupSnapshot backup = service.createBackup();
        execute(appDatabase, "delete from app_event");
        // Simulate residue from a crashed writer next to the database that will be replaced.
        Files.write(appDatabase.resolveSibling(appDatabase.getFileName() + "-wal"), new byte[] {1, 2, 3, 4});
        Files.write(appDatabase.resolveSibling(appDatabase.getFileName() + "-shm"), new byte[] {5, 6, 7, 8});
        assertTrue(Files.exists(appDatabase.resolveSibling(appDatabase.getFileName() + "-wal")));

        service.restoreBackup(backup.id());

        assertFalse(Files.exists(appDatabase.resolveSibling(appDatabase.getFileName() + "-wal")));
        assertFalse(Files.exists(appDatabase.resolveSibling(appDatabase.getFileName() + "-shm")));
        assertEquals(1, count(appDatabase, "select count(*) from app_event where message = 'keep me'"));
    }

    @Test
    void shouldInvalidateKnowledgeIndexAndClearVectorStoreAfterRestore() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        Path appDatabase = configuration.database().appDatabasePath();
        execute(appDatabase, """
            insert into knowledge_index_jobs(id, article_id, revision_id, status, created_at, updated_at)
            values ('job-1', 'article-1', 'revision-1', 'COMPLETED', '2026-07-01T00:00:00Z', '2026-07-01T00:00:00Z')
            """);
        AtomicBoolean vectorStoreCleared = new AtomicBoolean(false);
        SqliteApplicationBackupService service = new SqliteApplicationBackupService(
            configuration, () -> vectorStoreCleared.set(true));
        BackupSnapshot backup = service.createBackup();
        execute(appDatabase, "delete from knowledge_index_jobs");

        service.restoreBackup(backup.id());

        assertEquals("PENDING", singleValue(appDatabase, "select status from knowledge_index_jobs where id = 'job-1'"));
        assertTrue(vectorStoreCleared.get());
    }

    @Test
    void shouldKeepRestoredDatabaseReadableWhenIndexInvalidationFails() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        Path appDatabase = configuration.database().appDatabasePath();
        execute(appDatabase, "insert into app_event(event_type, message) values ('BEFORE', 'keep me')");
        SqliteApplicationBackupService service = new SqliteApplicationBackupService(
            configuration, () -> { throw new IllegalStateException("vector store is broken"); });
        BackupSnapshot backup = service.createBackup();
        execute(appDatabase, "delete from app_event");

        service.restoreBackup(backup.id());

        assertEquals(1, count(appDatabase, "select count(*) from app_event where message = 'keep me'"));
    }

    @Test
    void shouldSerializeConcurrentRestoreOperations() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        Path appDatabase = configuration.database().appDatabasePath();
        execute(appDatabase, "insert into app_event(event_type, message) values ('BEFORE', 'keep me')");
        SqliteApplicationBackupService service = new SqliteApplicationBackupService(configuration);
        BackupSnapshot backup = service.createBackup();
        execute(appDatabase, "delete from app_event");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable restore = () -> {
            try {
                start.await();
                service.restoreBackup(backup.id());
            } catch (Exception error) {
                failure.set(error);
            }
        };
        var first = workers.submit(restore);
        var second = workers.submit(restore);
        start.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        workers.shutdown();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(1, count(appDatabase, "select count(*) from app_event where message = 'keep me'"));
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

    private static String singleValue(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
