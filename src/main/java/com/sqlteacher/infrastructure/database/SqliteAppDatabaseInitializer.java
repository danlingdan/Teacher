package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteAppDatabaseInitializer implements DatabaseInitializationService {
    private static final Logger log = LoggerFactory.getLogger(SqliteAppDatabaseInitializer.class);

    private final SqlTeacherConfiguration properties;

    public SqliteAppDatabaseInitializer(SqlTeacherConfiguration properties) {
        this.properties = properties;
    }

    @Override
    public DatabaseInitializationResult initialize() {
        Path dataDirectory = properties.dataDirectory();
        Path appDatabase = properties.database().appDatabasePath();
        Path demoDatabase = properties.database().demoDatabasePath();

        try {
            migrateLegacyDataDirectory(dataDirectory);
            Files.createDirectories(dataDirectory);
            Files.createDirectories(appDatabase.toAbsolutePath().getParent());
            Files.createDirectories(demoDatabase.toAbsolutePath().getParent());

            boolean appCreated = Files.notExists(appDatabase);
            boolean demoCreated = Files.notExists(demoDatabase);

            initializeAppDatabaseWithRecovery(appDatabase, dataDirectory.resolve("exercise-sessions"));
            initializeDemoDatabase(demoDatabase);

            log.info("SQLite databases initialized, appDatabase={}, demoDatabase={}", appDatabase, demoDatabase);
            return new DatabaseInitializationResult(appDatabase, demoDatabase, appCreated, demoCreated);
        } catch (IOException | SQLException ex) {
            throw new SqlTeacherException("SQLITE_INIT_FAILED", "Failed to initialize SQLite databases", ex);
        }
    }

    private static void initializeAppDatabase(Path databasePath, Path sessionDirectory) throws SQLException, IOException {
        new SqliteSchemaMigrator().migrate(databasePath);
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.setAutoCommit(false);
            try {
                new DefaultExerciseCatalogSeeder().seed(connection);
                ExerciseSessionRuntimeCleaner.closeActiveSessions(connection, java.time.Instant.now());
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
        ExerciseSessionRuntimeCleaner.deleteSessionFiles(sessionDirectory);
    }

    private void initializeAppDatabaseWithRecovery(Path databasePath, Path sessionDirectory)
            throws SQLException, IOException {
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        boolean upgradeNeeded = Files.exists(databasePath)
            && migrator.currentVersion(databasePath) < migrator.latestVersion();
        String recoveryBackup = null;
        if (upgradeNeeded) {
            recoveryBackup = new SqliteApplicationBackupService(properties)
                .createAutomaticBackup("before-schema-" + migrator.latestVersion()).id();
        }
        try {
            initializeAppDatabase(databasePath, sessionDirectory);
        } catch (SQLException | IOException | RuntimeException error) {
            if (recoveryBackup != null) {
                try {
                    new SqliteApplicationBackupService(properties).restoreBackup(recoveryBackup);
                } catch (RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            throw error;
        }
    }

    private static void initializeDemoDatabase(Path databasePath) throws SQLException {
        createDemoDatabase(databasePath, false);
    }

    static void createDemoDatabase(Path databasePath, boolean reset) throws SQLException {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                create table if not exists student (
                    id integer primary key,
                    name text not null,
                    score integer not null
                )
                """);
            if (reset) {
                statement.executeUpdate("delete from student");
            }
            statement.executeUpdate("""
                insert into student(id, name, score)
                select 1, 'Alice', 92
                where not exists (select 1 from student where id = 1)
                """);
            statement.executeUpdate("""
                insert into student(id, name, score)
                select 2, 'Bob', 76
                where not exists (select 1 from student where id = 2)
                """);
        }
    }

    private static void migrateLegacyDataDirectory(Path dataDirectory) throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return;
        }
        Path expected = Path.of(localAppData, "SQLTeacher").toAbsolutePath().normalize();
        Path target = dataDirectory.toAbsolutePath().normalize();
        Path legacy = Path.of("app-data").toAbsolutePath().normalize();
        if (!target.equals(expected) || target.equals(legacy) || Files.notExists(legacy)
            || Files.exists(target.resolve("app.db"))) {
            return;
        }
        Files.createDirectories(target);
        try (var paths = Files.walk(legacy)) {
            for (Path source : paths.toList()) {
                Path relative = legacy.relativize(source);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Legacy data path escaped target directory");
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        log.info("Migrated legacy SQLTeacher data directory from {} to {}", legacy, target);
    }
}
