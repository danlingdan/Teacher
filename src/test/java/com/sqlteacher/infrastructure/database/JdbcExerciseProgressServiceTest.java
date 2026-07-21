package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.ExerciseProgressItem;
import com.sqlteacher.application.exercise.ExerciseProgressOverview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExerciseProgressServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldUseStableSubmissionAndPerExerciseProgressMetrics() throws Exception {
        DatabaseConfiguration databases = initialize();
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        insertProgressFixture(connections);
        JdbcExerciseProgressService service = new JdbcExerciseProgressService(connections);

        ExerciseProgressOverview overview = service.overview();
        List<ExerciseProgressItem> items = service.listExerciseProgress();

        assertEquals(2, overview.sessions());
        assertEquals(4, overview.attempts());
        assertEquals(3, overview.submissions());
        assertEquals(1, overview.passedSubmissions());
        assertEquals(1.0 / 3.0, overview.submissionPassRate(), 0.0001);
        assertEquals(Duration.ofMillis(200), overview.averageSubmissionDuration());
        assertEquals(3, overview.hintsUsed());
        assertEquals(1, overview.completedExercises());

        ExerciseProgressItem passed = items.stream().filter(item -> item.exerciseId().equals("query-01"))
            .findFirst().orElseThrow();
        assertEquals(3, passed.attempts());
        assertEquals(1, passed.failedSubmissions());
        assertTrue(passed.passed());
    }

    private DatabaseConfiguration initialize() {
        DatabaseConfiguration databases = new DatabaseConfiguration(
            tempDir.resolve("app.db"), tempDir.resolve("demo.db")
        );
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test"
            )
        )).initialize();
        return databases;
    }

    private static void insertProgressFixture(JdbcConnectionFactory connections) throws Exception {
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used)
                values ('s1', 'query-01', 1, '2026-07-21T01:00:00Z', 1),
                       ('s2', 'query-02', 1, '2026-07-21T02:00:00Z', 2)
                """);
            statement.executeUpdate("""
                insert into exercise_attempts(id, session_id, status, sql_text, execution_success, passed,
                    duration_ms, created_at)
                values ('a1', 's1', 'RUN', 'select 1', 1, null, 10, '2026-07-21T01:01:00Z'),
                       ('a2', 's1', 'FAILED', 'select 1', 1, 0, 100, '2026-07-21T01:02:00Z'),
                       ('a3', 's1', 'PASSED', 'select 1', 1, 1, 200, '2026-07-21T01:03:00Z'),
                       ('a4', 's2', 'FAILED', 'select 1', 1, 0, 300, '2026-07-21T02:01:00Z')
                """);
        }
    }
}
