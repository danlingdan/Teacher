package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.connection.FileDatabaseConnectionTarget;
import com.sqlteacher.application.connection.GenericJdbcConnectionTarget;
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

    @Test
    void shouldBuildEmbeddedAndGenericJdbcProfiles() {
        DatabaseConnectionProfile duckDb = ConnectionSettingsController.buildProfile(
            "course.duckdb", "Course DuckDB", DatabaseDialect.DUCKDB, "data/course.duckdb",
            "", "", "", "", false, true
        );
        assertEquals(DatabaseDialect.DUCKDB, ((FileDatabaseConnectionTarget) duckDb.target()).dialect());

        DatabaseConnectionProfile generic = ConnectionSettingsController.buildProfile(
            "course.generic", "Vendor JDBC", DatabaseDialect.GENERIC, "", "", "", "", "teacher",
            "jdbc:vendor:course", "com.vendor.Driver", "drivers/vendor.jar", true, true
        );
        GenericJdbcConnectionTarget target = (GenericJdbcConnectionTarget) generic.target();
        assertEquals("jdbc:vendor:course", target.jdbcUrl());
        assertEquals(Path.of("drivers", "vendor.jar"), target.driverJar());
    }
}
