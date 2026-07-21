package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSchemaMigratorTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldInitializeAnEmptyApplicationDatabase() throws Exception {
        Path database = tempDir.resolve("empty.db");

        int version = new SqliteSchemaMigrator().migrate(database);

        assertEquals(3, version);
        assertTrue(tableExists(database, "schema_version"));
        assertTrue(tableExists(database, "app_event"));
        assertTrue(tableExists(database, "learning_events"));
        assertTrue(tableExists(database, "connection_profiles"));
        assertTrue(tableExists(database, "connection_selection"));
        assertTrue(tableExists(database, "exercise_datasets"));
        assertTrue(tableExists(database, "exercises"));
        assertTrue(tableExists(database, "exercise_sessions"));
        assertTrue(tableExists(database, "exercise_attempts"));
        assertEquals(List.of(1, 2, 3), appliedVersions(database));
    }

    @Test
    void shouldAdoptTheUnversionedDemoBaselineWithoutLosingData() throws Exception {
        Path database = tempDir.resolve("legacy.db");
        execute(database, """
            create table app_event (
                id integer primary key autoincrement,
                event_type text not null,
                message text,
                created_at text not null default current_timestamp
            )
            """);
        execute(database, "insert into app_event(event_type, message) values ('BASELINE', 'keep me')");

        new SqliteSchemaMigrator().migrate(database);

        assertEquals(List.of(1, 2, 3), appliedVersions(database));
        assertEquals(1, countRows(database, "app_event"));
        assertTrue(tableExists(database, "learning_events"));
    }

    @Test
    void shouldBeIdempotentWhenRunRepeatedly() throws Exception {
        Path database = tempDir.resolve("repeat.db");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();

        migrator.migrate(database);
        execute(database, "insert into app_event(event_type, message) values ('FIRST_RUN', 'keep me')");
        int version = migrator.migrate(database);

        assertEquals(3, version);
        assertEquals(List.of(1, 2, 3), appliedVersions(database));
        assertEquals(1, countRows(database, "app_event"));
    }

    @Test
    void shouldRollbackAllPendingMigrationsWhenOneFails() throws Exception {
        Path database = tempDir.resolve("failure.db");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator(List.of(
            new SqliteSchemaMigrator.Migration(1, "Create stable table", List.of(
                "create table stable_table (id integer primary key)"
            )),
            new SqliteSchemaMigrator.Migration(2, "Run invalid SQL", List.of(
                "create table broken syntax"
            ))
        ));

        assertThrows(SQLException.class, () -> migrator.migrate(database));

        assertFalse(tableExists(database, "schema_version"));
        assertFalse(tableExists(database, "stable_table"));
    }

    @Test
    void shouldRejectADatabaseCreatedByANewerApplicationVersion() throws Exception {
        Path database = tempDir.resolve("future.db");
        execute(database, """
            create table schema_version (
                version integer primary key,
                description text not null,
                applied_at text not null default current_timestamp
            )
            """);
        execute(database, "insert into schema_version(version, description) values (1, 'baseline')");
        execute(database, "insert into schema_version(version, description) values (2, 'connections')");
        execute(database, "insert into schema_version(version, description) values (3, 'exercises')");
        execute(database, "insert into schema_version(version, description) values (4, 'future version')");

        SQLException error = assertThrows(
            SQLException.class,
            () -> new SqliteSchemaMigrator().migrate(database)
        );

        assertTrue(error.getMessage().contains("newer"));
        assertEquals(List.of(1, 2, 3, 4), appliedVersions(database));
    }

    private static void execute(Path database, String sql) throws Exception {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static boolean tableExists(Path database, String tableName) throws Exception {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "select count(*) from sqlite_master where type = 'table' and name = ?"
             )) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static List<Integer> appliedVersions(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select version from schema_version order by version")) {
            var versions = new java.util.ArrayList<Integer>();
            while (resultSet.next()) {
                versions.add(resultSet.getInt(1));
            }
            return List.copyOf(versions);
        }
    }

    private static int countRows(Path database, String tableName) throws Exception {
        if (!List.of("app_event").contains(tableName)) {
            throw new IllegalArgumentException("Unexpected test table: " + tableName);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
