package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionSettingsControllerTest {
    @Test
    void shouldBuildAValidatedSqliteProfile() {
        DatabaseConnectionProfile profile = ConnectionSettingsController.buildProfile(
            "course.sqlite", "Course SQLite", DatabaseDialect.SQLITE, " data/course.db ",
            "", "", "", "", false, true
        );

        assertEquals(Path.of("data", "course.db"), ((SqliteConnectionTarget) profile.target()).databasePath());
        assertTrue(profile.enabled());
    }

    @Test
    void shouldBuildAReadOnlyMysqlProfile() {
        DatabaseConnectionProfile profile = ConnectionSettingsController.buildProfile(
            "course.mysql", "Course MySQL", DatabaseDialect.MYSQL, "",
            "localhost", "3306", "course", "teacher", true, true
        );

        ServerConnectionTarget target = (ServerConnectionTarget) profile.target();
        assertEquals(3306, target.port());
        assertEquals("course", target.databaseName());
        assertTrue(profile.readOnly());
    }

    @Test
    void shouldRejectMissingPathAndInvalidPort() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionSettingsController.buildProfile(
                "course.sqlite", "Course", DatabaseDialect.SQLITE, " ",
                "", "", "", "", true, true
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionSettingsController.buildProfile(
                "course.mysql", "Course", DatabaseDialect.MYSQL, "",
                "localhost", "not-a-port", "course", "teacher", true, true
            )
        );
    }
}
