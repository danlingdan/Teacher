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

        assertSchemaObjectsMatchLegacyWithDocumentedDelta(legacy, current);
        assertColumnsMatchLegacyWithDocumentedDelta(legacy, current);
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

        // 迁移不得改动任何既有对象；允许增量仅限已记录的 Migration 9（班级码唯一索引）与
        // v3.7.0 Migration 10（提交载荷列），以及 SQLite 对建表 DDL 的 ALTER 重写
        // （见 assertDocumentedMigrationDelta）。
        List<String> after = schemaObjects(database);
        assertDocumentedMigrationDelta(before, after);
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
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), appliedVersions(interrupted));
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

    /**
     * The documented migration schema deltas: the v3.4.1 classroom join-code unique index,
     * the v3.7.0 assignment submission payload column, and the v3.8.0 role_grant_codes table
     * (ACC-S4) with its state index and the auto-index created by its blob primary key.
     * sqlite_master 存储的是 SQLite 规范化后的 DDL 文本（大写关键字、丢弃 if not exists）。
     */
    private static final String JOIN_CODE_INDEX_OBJECT =
        "index|idx_classrooms_join_code|classrooms|CREATE UNIQUE INDEX idx_classrooms_join_code "
            + "on classrooms(join_code)";
    private static final String ROLE_GRANT_TABLE_OBJECT =
        "table|role_grant_codes|role_grant_codes|CREATE TABLE role_grant_codes(code_hash blob primary key,"
            + "role text not null check(role in ('TEACHER')),created_by text not null references users(id),"
            + "created_at text not null,expires_at text not null,used_by text,used_at text,revoked_at text)";
    private static final String ROLE_GRANT_INDEX_OBJECT =
        "index|idx_role_grant_codes_state|role_grant_codes|CREATE INDEX idx_role_grant_codes_state "
            + "on role_grant_codes(revoked_at,used_at)";
    private static final String ROLE_GRANT_AUTOINDEX_OBJECT =
        "index|sqlite_autoindex_role_grant_codes_1|role_grant_codes|null";
    private static final String JOIN_CODE_COLUMN = "classrooms|join_code|TEXT|0|null|0";
    private static final String SUBMISSION_PAYLOAD_COLUMN =
        "assignment_submissions|submission_payload_json|TEXT|0|null|0";
    /** Migration 11 新表 role_grant_codes 的全部列（ACC-S4）。 */
    private static final Set<String> ROLE_GRANT_COLUMNS = Set.of(
        "role_grant_codes|code_hash|BLOB|0|null|1",
        "role_grant_codes|role|TEXT|1|null|0",
        "role_grant_codes|created_by|TEXT|1|null|0",
        "role_grant_codes|created_at|TEXT|1|null|0",
        "role_grant_codes|expires_at|TEXT|1|null|0",
        "role_grant_codes|used_by|TEXT|0|null|0",
        "role_grant_codes|used_at|TEXT|0|null|0",
        "role_grant_codes|revoked_at|TEXT|0|null|0");
    /** sqlite_master 中 classrooms 建表 DDL 行的定位前缀。 */
    private static final String CLASSROOMS_TABLE_MARKER = "|classrooms|classrooms|CREATE TABLE classrooms(";
    private static final String ASSIGNMENTS_TABLE_MARKER =
        "|assignment_submissions|assignment_submissions|CREATE TABLE assignment_submissions(";

    /**
     * 已记录的迁移增量仅为：Migration 9（班级码唯一索引、classrooms 增列）、v3.7.0
     * Migration 10（assignment_submissions 增列）与 v3.8.0 Migration 11（role_grant_codes
     * 表及其索引），以及 SQLite 对这两张表建表 DDL 的 ALTER 规范化重写。其余对象必须一致。
     */
    private static void assertDocumentedMigrationDelta(List<String> before, List<String> after) {
        List<String> beforeRest = withoutRewrittenTableDdl(before);
        List<String> afterRest = withoutRewrittenTableDdl(after);
        List<String> missing = beforeRest.stream()
            .filter(object -> !afterRest.contains(object)).toList();
        assertTrue(missing.isEmpty(),
            "migration must not alter legacy schema objects; missing after migration: " + missing);
        // sqlite_master 的行序取决于对象创建顺序，断言比较集合而非顺序。
        List<String> expectedNewObjects = List.of(JOIN_CODE_INDEX_OBJECT, ROLE_GRANT_TABLE_OBJECT,
            ROLE_GRANT_INDEX_OBJECT, ROLE_GRANT_AUTOINDEX_OBJECT);
        assertEquals(expectedNewObjects.stream().sorted().toList(),
            afterRest.stream().filter(object -> !beforeRest.contains(object)).sorted().toList(),
            "only the documented migration deltas may introduce new schema objects");

        String afterClassrooms = after.stream()
            .filter(object -> object.contains(CLASSROOMS_TABLE_MARKER)).findFirst().orElseThrow();
        assertTrue(afterClassrooms.contains("join_code"),
            "ALTER must append join_code to the classrooms DDL: " + afterClassrooms);
        String afterAssignments = after.stream()
            .filter(object -> object.contains(ASSIGNMENTS_TABLE_MARKER)).findFirst().orElseThrow();
        assertTrue(afterAssignments.contains("submission_payload_json"),
            "ALTER must append submission_payload_json to the assignment_submissions DDL: " + afterAssignments);
    }

    private static List<String> withoutRewrittenTableDdl(List<String> objects) {
        return objects.stream()
            .filter(object -> !object.contains(CLASSROOMS_TABLE_MARKER)
                && !object.contains(ASSIGNMENTS_TABLE_MARKER))
            .toList();
    }

    private static void assertSchemaObjectsMatchLegacyWithDocumentedDelta(Path legacy, Path current) {
        try {
            assertDocumentedMigrationDelta(schemaObjects(legacy), schemaObjects(current));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void assertColumnsMatchLegacyWithDocumentedDelta(Path legacy, Path current) {
        try {
            List<String> legacyColumns = tableColumns(legacy);
            List<String> currentColumns = tableColumns(current);
            List<String> missing = legacyColumns.stream()
                .filter(column -> !currentColumns.contains(column)).toList();
            assertTrue(missing.isEmpty(),
                "migration must not alter legacy table columns; missing in current: " + missing);
            var expectedColumns = new java.util.HashSet<>(Set.of(JOIN_CODE_COLUMN, SUBMISSION_PAYLOAD_COLUMN));
            expectedColumns.addAll(ROLE_GRANT_COLUMNS);
            assertEquals(expectedColumns,
            Set.copyOf(currentColumns.stream().filter(column -> !legacyColumns.contains(column)).toList()),
                "the only documented column deltas are classrooms.join_code, "
                    + "assignment_submissions.submission_payload_json and the role_grant_codes table");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** The exact startup order of SqlTeacherCloudServer with the current stores. */
    private static void currentStoreInitialization(Path database) throws Exception {
        new CloudAuthenticationStore(database);
        new CloudClassroomStore(database);
        new CloudAdministrationStore(database);
        new V14CloudStore(database);
        new V19CloudStore(database);
        new V110SupportStore(database);
        new V111AccountStore(database, new FileMailSender(Path.of(System.getProperty("java.io.tmpdir"))));
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
