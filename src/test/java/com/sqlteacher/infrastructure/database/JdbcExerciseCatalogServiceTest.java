package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.ExerciseCatalogItem;
import com.sqlteacher.application.exercise.WrongBookItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W2.2/W2.3: deterministic recommendation and the local wrong-answer book. */
class JdbcExerciseCatalogServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldAggregateWrongBookWithScoresAndRecommendNextDeterministically() throws Exception {
        SqlTeacherConfiguration configuration = configuration();
        new SqliteAppDatabaseInitializer(configuration).initialize();
        JdbcConnectionFactory connections = new JdbcConnectionFactory(configuration.database());
        var management = new JdbcExerciseManagementService(connections);
        var catalog = new JdbcExerciseCatalogService(connections, management, () -> "student-42");

        // query-02：两次失败尝试（得分 40、60），未通过 → 错题本。
        seedSession(configuration, "query-02", "student-42", """
            insert into exercise_attempts(id, session_id, status, sql_text, execution_success, passed,
                duration_ms, result_columns_json, result_rows_json, feedback_json, error_code, created_at, score)
            values ('a-1', '@SESSION@', 'FAILED', 'select 1', 1, 0, 1, '[]', '[]',
                '["rows：结果行数或部分值不符合要求。"]', 'RESULT_MISMATCH', '2026-09-10T01:00:00Z', 40);
            insert into exercise_attempts(id, session_id, status, sql_text, execution_success, passed,
                duration_ms, result_columns_json, result_rows_json, feedback_json, error_code, created_at, score)
            values ('a-2', '@SESSION@', 'FAILED', 'select 2', 1, 0, 1, '[]', '[]',
                '["rows：结果行数或部分值不符合要求。"]', 'RESULT_MISMATCH', '2026-09-10T02:00:00Z', 60)
            """);
        // query-01：一次通过 → 不进错题本。
        seedSession(configuration, "query-01", "student-42", """
            insert into exercise_attempts(id, session_id, status, sql_text, execution_success, passed,
                duration_ms, result_columns_json, result_rows_json, feedback_json, error_code, created_at, score)
            values ('a-3', '@SESSION@', 'PASSED', 'select 1', 1, 1, 1, '[]', '[]', '[]', null,
                '2026-09-10T03:00:00Z', 100)
            """);

        List<WrongBookItem> wrongBook = catalog.wrongBook();
        assertEquals(1, wrongBook.size());
        WrongBookItem item = wrongBook.getFirst();
        assertEquals("query-02", item.exerciseId());
        assertEquals(2, item.attempts());
        assertEquals(60, item.bestScore());
        assertTrue(item.lastFeedback().contains("rows"), item.lastFeedback());

        List<ExerciseCatalogItem> items = catalog.listAvailableExercises();
        ExerciseCatalogItem failed = items.stream()
            .filter(candidate -> candidate.id().equals("query-02")).findFirst().orElseThrow();
        assertEquals(60, failed.bestScore());
        assertEquals(2, failed.attempts());
        ExerciseCatalogItem passed = items.stream()
            .filter(candidate -> candidate.id().equals("query-01")).findFirst().orElseThrow();
        assertEquals(100, passed.bestScore());
        assertTrue(passed.passed());

        // 推荐：query-02 正确率 0% → 低分桶 → 巩固基础文案；同一输入必须同一推荐。
        var first = catalog.recommendNextExercise().orElseThrow();
        var second = catalog.recommendNextExercise().orElseThrow();
        assertEquals(first, second);
        assertEquals("query-02", first.exerciseId());
        assertTrue(first.reason().contains("0%") || first.reason().contains("巩固"), first.reason());
    }

    private void seedSession(
        SqlTeacherConfiguration configuration,
        String exerciseId,
        String ownerId,
        String attemptsSql
    ) throws Exception {
        String sessionId = "s-" + exerciseId + "-" + ownerId;
        try (Connection connection = DriverManager.getConnection(
            "jdbc:sqlite:" + configuration.database().appDatabasePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used, owner_id) "
                    + "values ('" + sessionId + "', '" + exerciseId + "', 1, '2026-09-10T00:30:00Z', 0, '"
                    + ownerId + "')"
            );
            for (String sql : attemptsSql.split(";")) {
                String trimmed = sql.strip().replace("@SESSION@", sessionId);
                if (!trimmed.isEmpty() && trimmed.startsWith("insert")) {
                    statement.executeUpdate(trimmed);
                }
            }
        }
    }

    private SqlTeacherConfiguration configuration() {
        DatabaseConfiguration databases = new DatabaseConfiguration(
            tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        return new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1),
                Duration.ofSeconds(2), "test")
        );
    }
}
