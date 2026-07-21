package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.maintenance.BackupSnapshot;
import com.sqlteacher.domain.SqlTeacherException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SqliteApplicationBackupService implements ApplicationBackupService {
    private static final DateTimeFormatter ID_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int MAX_BACKUPS = 20;

    private final SqlTeacherConfiguration configuration;
    private final Path backupDirectory;

    public SqliteApplicationBackupService(SqlTeacherConfiguration configuration) {
        this.configuration = configuration;
        this.backupDirectory = configuration.dataDirectory().resolve("backups").toAbsolutePath().normalize();
    }

    @Override
    public BackupSnapshot createBackup() {
        return createBackup(false, "manual");
    }

    BackupSnapshot createAutomaticBackup(String reason) {
        String normalizedReason = reason == null ? "upgrade" : reason.replaceAll("[^a-zA-Z0-9-]", "-");
        return createBackup(true, "auto-" + normalizedReason);
    }

    @Override
    public List<BackupSnapshot> listBackups() {
        if (Files.notExists(backupDirectory)) {
            return List.of();
        }
        try (var paths = Files.list(backupDirectory)) {
            return paths
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .map(this::snapshot)
                .sorted(Comparator.comparing(BackupSnapshot::createdAt).reversed())
                .toList();
        } catch (IOException error) {
            throw failure("BACKUP_LIST_FAILED", "Failed to list application backups", error);
        }
    }

    @Override
    public void restoreBackup(String backupId) {
        Path archive = resolveBackup(backupId);
        if (Files.notExists(archive)) {
            throw new SqlTeacherException("BACKUP_NOT_FOUND", "The selected backup no longer exists");
        }
        Path restoreDirectory = configuration.dataDirectory().resolve(".restore-" + UUID.randomUUID()).normalize();
        String safetyBackupId = null;
        boolean replacementStarted = false;
        try {
            Files.createDirectories(restoreDirectory);
            extractDatabaseFiles(archive, restoreDirectory);
            Path restoredApp = restoreDirectory.resolve("app.db");
            Path restoredDemo = restoreDirectory.resolve("demo.db");
            if (Files.notExists(restoredApp)) {
                throw new IOException("Backup does not contain app.db");
            }
            verifyIntegrity(restoredApp);
            if (Files.exists(restoredDemo)) {
                verifyIntegrity(restoredDemo);
            }

            safetyBackupId = createAutomaticBackup("before-restore").id();
            replacementStarted = true;
            replaceDatabase(restoredApp, configuration.database().appDatabasePath());
            if (Files.exists(restoredDemo)) {
                replaceDatabase(restoredDemo, configuration.database().demoDatabasePath());
            }
        } catch (IOException | SQLException error) {
            if (replacementStarted && safetyBackupId != null) {
                try {
                    Path rollbackDirectory = restoreDirectory.resolve("rollback");
                    Files.createDirectories(rollbackDirectory);
                    extractDatabaseFiles(resolveBackup(safetyBackupId), rollbackDirectory);
                    replaceDatabase(rollbackDirectory.resolve("app.db"), configuration.database().appDatabasePath());
                    Path rollbackDemo = rollbackDirectory.resolve("demo.db");
                    if (Files.exists(rollbackDemo)) {
                        replaceDatabase(rollbackDemo, configuration.database().demoDatabasePath());
                    }
                } catch (IOException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            throw failure("BACKUP_RESTORE_FAILED", "Failed to restore the selected backup", error);
        } finally {
            deleteTreeQuietly(restoreDirectory);
        }
    }

    @Override
    public void restoreDemoDatabase() {
        Path staged = configuration.dataDirectory().resolve(".demo-restore-" + UUID.randomUUID() + ".db");
        try {
            SqliteAppDatabaseInitializer.createDemoDatabase(staged, true);
            verifyIntegrity(staged);
            replaceDatabase(staged, configuration.database().demoDatabasePath());
        } catch (IOException | SQLException error) {
            throw failure("DEMO_RESTORE_FAILED", "Failed to restore the demonstration database", error);
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // The staged file is harmless and will be replaced on the next restore attempt.
            }
        }
    }

    private BackupSnapshot createBackup(boolean automatic, String prefix) {
        Instant now = Instant.now();
        String id = prefix + "-" + ID_TIME.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path archive = resolveBackup(id);
        Path staging = configuration.dataDirectory().resolve(".backup-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(backupDirectory);
            Files.createDirectories(staging);
            Path appCopy = staging.resolve("app.db");
            Path demoCopy = staging.resolve("demo.db");
            createConsistentCopy(configuration.database().appDatabasePath(), appCopy);
            createConsistentCopy(configuration.database().demoDatabasePath(), demoCopy);
            if (Files.notExists(appCopy)) {
                throw new IOException("Application database does not exist");
            }
            try (OutputStream output = Files.newOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                addToArchive(zip, appCopy, "app.db");
                if (Files.exists(demoCopy)) {
                    addToArchive(zip, demoCopy, "demo.db");
                }
            }
            pruneOldBackups();
            return new BackupSnapshot(id, now, Files.size(archive), automatic);
        } catch (IOException | SQLException error) {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw failure("BACKUP_CREATE_FAILED", "Failed to create an application backup", error);
        } finally {
            deleteTreeQuietly(staging);
        }
    }

    private static void createConsistentCopy(Path source, Path target) throws SQLException, IOException {
        if (Files.notExists(source)) {
            return;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            String escaped = target.toAbsolutePath().toString().replace("'", "''");
            statement.execute("vacuum into '" + escaped + "'");
        }
    }

    private static void verifyIntegrity(Path database) throws SQLException {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("pragma integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite integrity check failed for restored database");
            }
        }
    }

    private static void addToArchive(ZipOutputStream zip, Path source, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private static void extractDatabaseFiles(Path archive, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && ("app.db".equals(entry.getName()) || "demo.db".equals(entry.getName()))) {
                    Files.copy(zip, destination.resolve(entry.getName()), StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static void replaceDatabase(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path staged = destination.resolveSibling(destination.getFileName() + ".restore-staged");
        Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolveBackup(String backupId) {
        if (backupId == null || !backupId.matches("[a-zA-Z0-9._-]+")) {
            throw new SqlTeacherException("BACKUP_ID_INVALID", "Invalid backup identifier");
        }
        Path resolved = backupDirectory.resolve(backupId + ".zip").normalize();
        if (!resolved.getParent().equals(backupDirectory)) {
            throw new SqlTeacherException("BACKUP_ID_INVALID", "Invalid backup identifier");
        }
        return resolved;
    }

    private BackupSnapshot snapshot(Path archive) {
        try {
            String fileName = archive.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - 4);
            FileTime modified = Files.getLastModifiedTime(archive);
            return new BackupSnapshot(id, modified.toInstant(), Files.size(archive), id.startsWith("auto-"));
        } catch (IOException error) {
            throw failure("BACKUP_READ_FAILED", "Failed to read application backup metadata", error);
        }
    }

    private void pruneOldBackups() throws IOException {
        List<BackupSnapshot> snapshots = new ArrayList<>(listBackups());
        for (int index = MAX_BACKUPS; index < snapshots.size(); index++) {
            Files.deleteIfExists(resolveBackup(snapshots.get(index).id()));
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private static SqlTeacherException failure(String code, String message, Exception cause) {
        return new SqlTeacherException(code, message, cause);
    }
}
