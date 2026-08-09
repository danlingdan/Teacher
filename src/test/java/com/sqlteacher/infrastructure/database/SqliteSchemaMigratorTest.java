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

        assertEquals(18, version);
        assertTrue(tableExists(database, "schema_version"));
        assertTrue(tableExists(database, "app_event"));
        assertTrue(tableExists(database, "learning_events"));
        assertTrue(tableExists(database, "connection_profiles"));
        assertTrue(tableExists(database, "connection_selection"));
        assertTrue(tableExists(database, "exercise_datasets"));
        assertTrue(tableExists(database, "exercises"));
        assertTrue(tableExists(database, "exercise_sessions"));
        assertTrue(tableExists(database, "exercise_attempts"));
        assertTrue(tableExists(database, "knowledge_documents"));
        assertTrue(tableExists(database, "knowledge_chunks"));
        assertTrue(tableExists(database, "knowledge_chunks_fts"));
        assertTrue(tableExists(database, "assignment_submission_queue"));
        assertTrue(tableExists(database, "teaching_content_cache"));
        assertTrue(tableExists(database, "mastery_snapshot"));
        assertTrue(tableExists(database, "learning_action_state"));
        assertTrue(tableExists(database, "intervention_state"));
        assertTrue(tableExists(database, "course_knowledge_articles"));
        assertTrue(tableExists(database, "course_knowledge_revisions"));
        assertTrue(tableExists(database, "course_knowledge_point_links"));
        assertTrue(tableExists(database, "knowledge_chunks_v2"));
        assertTrue(tableExists(database, "knowledge_index_jobs"));
        assertTrue(tableExists(database, "knowledge_read_state"));
        assertTrue(tableExists(database, "course_objective_cache"));
        assertTrue(tableExists(database, "study_plan_snapshot"));
        assertTrue(tableExists(database, "study_plan_action"));
        assertTrue(tableExists(database, "study_plan_outbox"));
        assertTrue(tableExists(database, "grounded_tutor_session"));
        assertTrue(tableExists(database, "course_definition"));
        assertTrue(tableExists(database, "course_section"));
        assertTrue(tableExists(database, "knowledge_point_definition"));
        assertTrue(tableExists(database, "knowledge_point_relation"));
        assertTrue(tableExists(database, "learning_activity_definition"));
        assertTrue(tableExists(database, "activity_session"));
        assertTrue(tableExists(database, "activity_knowledge_point"));
        assertTrue(tableExists(database, "activity_evaluation_result"));
        assertTrue(tableExists(database, "activity_feedback"));
        assertTrue(tableExists(database, "course_content_provenance"));
        assertTrue(tableExists(database, "cross_course_knowledge_relation"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18), appliedVersions(database));
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

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18), appliedVersions(database));
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

        assertEquals(18, version);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18), appliedVersions(database));
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
        execute(database, "insert into schema_version(version, description) values (4, 'knowledge')");
        execute(database, "insert into schema_version(version, description) values (5, 'assignment queue')");
        execute(database, "insert into schema_version(version, description) values (6, 'teaching cache')");
        execute(database, "insert into schema_version(version, description) values (7, 'learning diagnosis')");
        execute(database, "insert into schema_version(version, description) values (8, 'course knowledge')");
        execute(database, "insert into schema_version(version, description) values (9, 'hybrid knowledge')");
        execute(database, "insert into schema_version(version, description) values (10, 'study planning')");
        execute(database, "insert into schema_version(version, description) values (11, 'activities')");
        execute(database, "insert into schema_version(version, description) values (12, 'binary tree activities')");
        execute(database, "insert into schema_version(version, description) values (13, 'programming activities')");
        execute(database, "insert into schema_version(version, description) values (14, 'simulation activities')");
        execute(database, "insert into schema_version(version, description) values (15, 'professional foundations')");
        execute(database, "insert into schema_version(version, description) values (16, 'project learning')");
        execute(database, "insert into schema_version(version, description) values (17, 'beta contracts')");
        execute(database, "insert into schema_version(version, description) values (18, 'compatibility profiles')");
        execute(database, "insert into schema_version(version, description) values (19, 'future version')");

        SQLException error = assertThrows(
            SQLException.class,
            () -> new SqliteSchemaMigrator().migrate(database)
        );

        assertTrue(error.getMessage().contains("newer"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), appliedVersions(database));
    }

    @Test
    void shouldBackfillSqlExercisesAsActivitiesAndRejectUnknownTypes() throws Exception {
        Path database = tempDir.resolve("activities.db");
        new SqliteSchemaMigrator().migrate(database);
        execute(database, """
            insert into exercise_datasets(id, name, setup_sql, version)
            values ('dataset', 'Dataset', 'create table sample(id integer);', 1)
            """);
        execute(database, """
            insert into exercises(
                id, title, description, knowledge_point, difficulty, dataset_id, reference_sql,
                evaluation_rule_json, hints_json, version, enabled
            ) values (
                'sql-1', 'SQL activity', 'SQL activity', 'Filtering', 'BEGINNER', 'dataset',
                'select id from sample',
                '{"compareColumns":true,"compareRows":true,"rowOrderMatters":false,"expectedRowCount":null,"requiredSqlKeywords":[]}',
                '[]', 1, 1
            )
            """);

        assertEquals(1, countRows(database, "exercises"));
        assertEquals(19, countRows(database, "learning_activity_definition"));
        assertEquals(20, countRows(database, "activity_knowledge_point"));
        assertEquals(12, countRows(database, "course_definition"));
        assertEquals(12, countRows(database, "course_content_provenance"));
        assertEquals(8, scalar(database, "select count(distinct activity_type) from learning_activity_definition"));
        assertEquals(3, countRows(database, "cross_course_knowledge_relation"));
        assertThrows(SQLException.class, () -> execute(database, """
            insert into learning_activity_definition(
                id, course_id, section_id, activity_type, title, description, difficulty,
                estimated_minutes, definition_version, specification_format_version,
                specification_json, source_kind, source_id, enabled, created_at, updated_at
            ) values (
                'unknown', 'builtin-data-management', 'sql-practice', 'UNKNOWN', 'Unknown', 'Unknown',
                'BEGINNER', 1, 1, 1, '{}', 'TEST', 'unknown', 1, current_timestamp, current_timestamp
            )
            """));
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
        if (!List.of(
                "app_event", "exercises", "learning_activity_definition",
                "activity_knowledge_point", "course_definition", "course_content_provenance",
                "cross_course_knowledge_relation"
            ).contains(tableName)) {
            throw new IllegalArgumentException("Unexpected test table: " + tableName);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int scalar(Path database, String sql) throws Exception {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.getInt(1);
        }
    }
}
