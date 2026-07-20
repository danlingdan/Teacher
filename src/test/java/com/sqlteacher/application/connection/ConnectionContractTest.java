package com.sqlteacher.application.connection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionContractTest {
    @Test
    void shouldModelSqliteAndServerTargetsWithoutJdbcDetails() {
        DatabaseConnectionProfile sqlite = new DatabaseConnectionProfile(
            "demo",
            " Demo database ",
            new SqliteConnectionTarget(Path.of("app-data", "..", "app-data", "demo.db")),
            false,
            true
        );
        DatabaseConnectionProfile mysql = new DatabaseConnectionProfile(
            "course.mysql",
            "Course database",
            new ServerConnectionTarget(DatabaseDialect.MYSQL, " localhost ", 3306, " course ", " teacher "),
            true,
            true
        );

        assertEquals("Demo database", sqlite.displayName());
        assertEquals(Path.of("app-data", "demo.db"), ((SqliteConnectionTarget) sqlite.target()).databasePath());
        assertEquals(DatabaseDialect.SQLITE, sqlite.dialect());
        assertEquals("localhost", ((ServerConnectionTarget) mysql.target()).host());
        assertEquals(DatabaseDialect.MYSQL, mysql.dialect());
    }

    @Test
    void shouldRejectInvalidProfileAndServerFields() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new DatabaseConnectionProfile(
                "invalid id",
                "Demo",
                new SqliteConnectionTarget(Path.of("demo.db")),
                false,
                true
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ServerConnectionTarget(DatabaseDialect.SQLITE, "localhost", 3306, "course", "teacher")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ServerConnectionTarget(DatabaseDialect.MYSQL, "localhost", 0, "course", "teacher")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ServerConnectionTarget(DatabaseDialect.MARIADB, " ", 3306, "course", "teacher")
        );
    }

    @Test
    void shouldKeepPersistentProfilesFreeOfPasswordFields() {
        boolean containsPassword = Arrays.stream(DatabaseConnectionProfile.class.getRecordComponents())
            .map(RecordComponent::getName)
            .anyMatch(name -> name.toLowerCase().contains("password"));
        boolean targetContainsPassword = Arrays.stream(ServerConnectionTarget.class.getRecordComponents())
            .map(RecordComponent::getName)
            .anyMatch(name -> name.toLowerCase().contains("password"));

        assertFalse(containsPassword);
        assertFalse(targetContainsPassword);
    }

    @Test
    void shouldValidateConnectionTestResult() {
        DatabaseConnectionTestResult result = new DatabaseConnectionTestResult(
            true,
            " Connected ",
            " MySQL ",
            " 8.4 ",
            Duration.ofMillis(25)
        );

        assertEquals("Connected", result.message());
        assertEquals("MySQL", result.databaseProduct());
        assertEquals("8.4", result.databaseVersion());
        assertThrows(
            IllegalArgumentException.class,
            () -> new DatabaseConnectionTestResult(false, "failed", "", "", Duration.ofMillis(-1))
        );
    }
}
