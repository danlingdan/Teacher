package com.sqlteacher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-alignment gate for the v3.4.0 REF-5 cutover to {@link CloudSchemaMigrator}: the new
 * versioned path must produce exactly the same schema objects (tables, columns, indexes —
 * compared via sqlite_master and per-table pragma table_info) as the legacy per-store
 * initializers preserved in {@link LegacyCloudSchemaInitializer}, for fresh databases, for
 * databases the legacy code already fully provisioned, and for databases whose legacy
 * provisioning was interrupted. The v3.1 exercise-bank store is unchanged and participates in
 * both paths.
 */
class CloudSchemaMigratorTest {
    @TempDir Path directory;

    @Test
    void freshDatabaseSchemaMatchesLegacyInitializer() throws Exception {
        Path legacy = directory.resolve("legacy.db");
        Path current = directory.resolve("current.db");

        legacyInitialization(legacy);
        currentStoreInitialization(current);

        assertEquals(schemaObjects(legacy), schemaObjects(current),
            "fresh-database schema objects must be identical to the legacy initializers");
        assertEquals(tableColumns(legacy), tableColumns(current),
            "fresh-database table columns must be identical to the legacy initializers");
        assertEquals(userVersion(legacy), userVersion(current));
        assertVersionRows(legacy, 6);
        assertVersionRows(current, CloudSchemaMigrator.latestVersion());
    }

    @Test
    void existingLegacyDatabaseIsPreservedAndExtended() throws Exception {
        Path database = directory.resolve("prod.db");
        legacyInitialization(database);
        List<String> before = schemaObjects(database);

        currentStoreInitialization(database);

        assertEquals(before, schemaObjects(database),
            "migrating an already-provisioned database must not change any schema object");
        assertEquals(userVersion(database), 2);
        assertVersionRows(database, CloudSchemaMigrator.latestVersion());
    }

    @Test
    void interruptedLegacyProvisioningIsCompleted() throws Exception {
        // Simulates a crash between the v1.4 initializer (stamps 1,2,3,4,6) and the v1.9
        // initializer (stamps 5): versions exist out of order and tables are missing.
        Path interrupted = directory.resolve("interrupted.db");
        LegacyCloudSchemaInitializer.core(interrupted);
        LegacyCloudSchemaInitializer.v14(interrupted);
        assertEquals(Set.of(1, 2, 3, 4, 6), appliedVersions(interrupted));

        Path golden = directory.resolve("golden.db");
        currentStoreInitialization(golden);

        currentStoreInitialization(interrupted);

        assertEquals(schemaObjects(golden), schemaObjects(interrupted),
            "resuming an interrupted legacy provisioning must converge to the same schema");
        assertEquals(tableColumns(golden), tableColumns(interrupted));
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8), appliedVersions(interrupted));
    }

    @Test
    void repeatedMigrationIsIdempotent() throws Exception {
        Path database = directory.resolve("idempotent.db");
        currentStoreInitialization(database);
        List<String> once = schemaObjects(database);

        currentStoreInitialization(database);
        currentStoreInitialization(database);

        assertEquals(once, schemaObjects(database));
    }

    @Test
    void futureSchemaIsRejected() throws Exception {
        Path database = directory.resolve("future.db");
        legacyInitialization(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into cloud_schema_version(version,description,applied_at) values(99,'future',current_timestamp)");
        }
        SQLException rejected = assertThrows(SQLException.class, () -> currentStoreInitialization(database));
        assertTrue(rejected.getMessage().contains("newer than this SQLTeacher version"));
    }

    @Test
    void tamperedHistoryIsRejected() throws Exception {
        Path database = directory.resolve("tampered.db");
        legacyInitialization(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                "update cloud_schema_version set description='hand-edited' where version=3");
        }
        SQLException rejected = assertThrows(SQLException.class, () -> currentStoreInitialization(database));
        assertTrue(rejected.getMessage().contains("migration history is invalid"));
    }

    /** The exact startup order of SqlTeacherCloudServer with the current stores. */
    private static void currentStoreInitialization(Path database) throws Exception {
        new CloudAuthenticationStore(database);
        new CloudClassroomStore(database);
        new CloudAdministrationStore(database);
        new V14CloudStore(database);
        new V19CloudStore(database);
        new V110SupportStore(database);
        new V111AccountStore(database, new FileMailSender(Path.of(System.getProperty("java.io.tmpdir"))), null);
        new V31ExerciseBankStore(database);
    }

    private static void legacyInitialization(Path database) throws Exception {
        LegacyCloudSchemaInitializer.core(database);
        LegacyCloudSchemaInitializer.v14(database);
        LegacyCloudSchemaInitializer.v19(database);
        LegacyCloudSchemaInitializer.v110(database);
        LegacyCloudSchemaInitializer.v111(database);
        new V31ExerciseBankStore(database);
    }

    private static List<String> schemaObjects(Path database) throws Exception {
        List<String> rows = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select type,name,tbl_name,sql from sqlite_master order by type,name")) {
            while (result.next()) {
                rows.add(result.getString(1) + "|" + result.getString(2) + "|"
                    + result.getString(3) + "|" + result.getString(4));
            }
        }
        return rows;
    }

    private static List<String> tableColumns(Path database) throws Exception {
        List<String> tables = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        List<String> columns = new ArrayList<>();
        for (String table : tables) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("pragma table_info(" + table + ")")) {
                while (result.next()) {
                    columns.add(table + "|" + result.getString("name") + "|" + result.getString("type")
                        + "|" + result.getInt("notnull") + "|" + result.getString("dflt_value")
                        + "|" + result.getInt("pk"));
                }
            }
        }
        return columns;
    }

    private static int userVersion(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            return statement.executeQuery("pragma user_version").getInt(1);
        }
    }

    private static Set<Integer> appliedVersions(Path database) throws Exception {
        Set<Integer> versions = new LinkedHashSet<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select version from cloud_schema_version order by version")) {
            while (result.next()) {
                versions.add(result.getInt(1));
            }
        }
        return versions;
    }

    /** Asserts versions 1..expected are applied with this build's descriptions. */
    private static void assertVersionRows(Path database, int expected) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select version,description from cloud_schema_version order by version")) {
            for (int version = 1; version <= expected; version++) {
                assertTrue(result.next(), "version " + version + " must be recorded");
                assertEquals(version, result.getInt(1));
                assertEquals(expectedVersionDescription(version), result.getString(2));
            }
        }
    }

    private static String expectedVersionDescription(int version) {
        return CloudSchemaMigrator.versionDescription(version);
    }
}
