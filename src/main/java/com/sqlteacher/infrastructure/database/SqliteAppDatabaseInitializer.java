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
            Files.createDirectories(dataDirectory);
            Files.createDirectories(appDatabase.toAbsolutePath().getParent());
            Files.createDirectories(demoDatabase.toAbsolutePath().getParent());

            boolean appCreated = Files.notExists(appDatabase);
            boolean demoCreated = Files.notExists(demoDatabase);

            initializeAppDatabase(appDatabase);
            initializeDemoDatabase(demoDatabase);

            log.info("SQLite databases initialized, appDatabase={}, demoDatabase={}", appDatabase, demoDatabase);
            return new DatabaseInitializationResult(appDatabase, demoDatabase, appCreated, demoCreated);
        } catch (IOException | SQLException ex) {
            throw new SqlTeacherException("SQLITE_INIT_FAILED", "Failed to initialize SQLite databases", ex);
        }
    }

    private static void initializeAppDatabase(Path databasePath) throws SQLException {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                create table if not exists app_event (
                    id integer primary key autoincrement,
                    event_type text not null,
                    message text,
                    created_at text not null default current_timestamp
                )
                """);
            statement.executeUpdate("""
                create table if not exists learning_events (
                    id integer primary key autoincrement,
                    event_type text not null,
                    occurred_at text not null,
                    connection_id text not null,
                    successful integer not null,
                    attributes text,
                    created_at text not null default current_timestamp
                )
                """);
        }
    }

    private static void initializeDemoDatabase(Path databasePath) throws SQLException {
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
}
