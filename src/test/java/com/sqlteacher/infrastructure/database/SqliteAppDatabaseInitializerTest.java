package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.infrastructure.config.AiModelProperties;
import com.sqlteacher.infrastructure.config.DatabaseProperties;
import com.sqlteacher.infrastructure.config.SqlTeacherProperties;
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

class SqliteAppDatabaseInitializerTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldInitializeAppAndDemoDatabases() throws Exception {
        Path appDb = tempDir.resolve("app.db");
        Path demoDb = tempDir.resolve("demo.db");
        SqlTeacherProperties properties = new SqlTeacherProperties(
            "SQLTeacher",
            tempDir,
            new DatabaseProperties(appDb, demoDb),
            new AiModelProperties(URI.create("http://localhost:11434"), Duration.ofSeconds(1))
        );

        DatabaseInitializationResult result = new SqliteAppDatabaseInitializer(properties).initialize();

        assertTrue(result.appDatabaseCreated());
        assertTrue(result.demoDatabaseCreated());
        assertTrue(Files.exists(appDb));
        assertTrue(Files.exists(demoDb));
        assertEquals(2, countDemoStudents(demoDb));
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
