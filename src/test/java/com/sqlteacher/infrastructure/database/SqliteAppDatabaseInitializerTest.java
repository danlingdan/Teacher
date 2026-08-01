package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SqliteAppDatabaseInitializerTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldInitializeAppAndDemoDatabases() throws Exception {
        Path appDb = tempDir.resolve("app.db");
        Path demoDb = tempDir.resolve("demo.db");
        SqlTeacherConfiguration properties = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            new DatabaseConfiguration(appDb, demoDb),
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(30), "test-model")
        );

        DatabaseInitializationResult result = new SqliteAppDatabaseInitializer(properties).initialize();

        assertTrue(result.appDatabaseCreated());
        assertTrue(result.demoDatabaseCreated());
        assertTrue(Files.exists(appDb));
        assertTrue(Files.exists(demoDb));
        assertEquals(10, readSchemaVersion(appDb));
        assertEquals(20, countExercises(appDb));
        assertEquals(20, countExercisesWithThreeHints(appDb));
        assertEquals(2, countDemoStudents(demoDb));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             var statement = connection.prepareStatement(
                 "update exercises set hints_json = ?, version = 1 where id = 'query-01'"
             )) {
            statement.setString(1, new ExercisePackageCodec().encodeHints(
                java.util.List.of("先写 SELECT 和 FROM。", "使用 ORDER BY id。")
            ));
            statement.executeUpdate();
        }
        new SqliteAppDatabaseInitializer(properties).initialize();
        assertEquals(20, countExercisesWithThreeHints(appDb));
    }

    @Test
    void shouldRecoverStaleExerciseSessionsAfterInterruptedExit() throws Exception {
        Path appDb = tempDir.resolve("recovery-app.db");
        Path demoDb = tempDir.resolve("recovery-demo.db");
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            new DatabaseConfiguration(appDb, demoDb),
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(30), "test-model"
            )
        );
        SqliteAppDatabaseInitializer initializer = new SqliteAppDatabaseInitializer(configuration);
        initializer.initialize();
        String sessionId = "11111111-1111-4111-8111-111111111111";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used)
                values ('11111111-1111-4111-8111-111111111111', 'query-01', 1, '2026-07-21T00:00:00Z', 0)
                """);
        }
        Path staleFile = tempDir.resolve("exercise-sessions").resolve(sessionId + ".db");
        Files.createDirectories(staleFile.getParent());
        Files.writeString(staleFile, "stale");

        initializer.initialize();

        assertFalse(Files.exists(staleFile));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select completed_at from exercise_sessions where id = '" + sessionId + "'"
             )) {
            assertTrue(result.next());
            assertTrue(result.getString(1) != null);
        }
    }

    private static int countExercises(Path appDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from exercises")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    @Test
    void shouldPreserveFilesCreatedDuringLegacyDataMigration() throws Exception {
        Path legacy = tempDir.resolve("legacy");
        Path target = tempDir.resolve("target");
        Files.createDirectories(legacy.resolve("logs"));
        Files.createDirectories(target.resolve("logs"));
        Files.writeString(legacy.resolve("app.db"), "legacy-database");
        Files.writeString(legacy.resolve("logs/sqlteacher.log"), "legacy-log");
        Files.writeString(target.resolve("logs/sqlteacher.log"), "active-log");

        SqliteAppDatabaseInitializer.copyMissingLegacyFiles(legacy, target);

        assertEquals("legacy-database", Files.readString(target.resolve("app.db")));
        assertEquals("active-log", Files.readString(target.resolve("logs/sqlteacher.log")));
    }

    private static int countExercisesWithThreeHints(Path appDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select count(*) from exercises where json_array_length(hints_json) = 3 and version = 2"
             )) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int readSchemaVersion(Path appDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select max(version) from schema_version")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countDemoStudents(Path demoDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + demoDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from student")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
