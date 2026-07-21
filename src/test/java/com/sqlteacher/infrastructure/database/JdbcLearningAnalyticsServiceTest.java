package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.analytics.AnalyticsCsvExport;
import com.sqlteacher.application.analytics.AnalyticsFilter;
import com.sqlteacher.application.analytics.LearningAnalyticsReport;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLearningAnalyticsServiceTest {
    @TempDir Path tempDir;

    @Test
    void shouldReproduceFilteredMetricsAndCsvFromTheSameReport() throws Exception {
        JdbcConnectionFactory connections = initialize();
        insertFixture(connections);
        JdbcLearningAnalyticsService service = new JdbcLearningAnalyticsService(
            connections,
            Clock.fixed(Instant.parse("2026-07-21T10:00:00Z"), ZoneOffset.UTC)
        );
        AnalyticsFilter filter = new AnalyticsFilter(
            Instant.parse("2026-07-21T00:00:00Z"), Instant.parse("2026-07-22T00:00:00Z"),
            null, "基础查询", null
        );

        LearningAnalyticsReport report = service.analyze(filter);
        AnalyticsCsvExport export = service.exportCsv(filter);

        assertEquals(1, report.overview().sessions());
        assertEquals(3, report.overview().attempts());
        assertEquals(2, report.overview().submissions());
        assertEquals(1, report.overview().passedSubmissions());
        assertEquals(0.5, report.overview().passRate(), 0.0001);
        assertEquals(1, report.overview().completedExercises());
        assertEquals(1, report.overview().totalExercises());
        assertEquals(Duration.ofMillis(150), report.overview().averageSubmissionDuration());
        assertTrue(report.commonErrors().stream().anyMatch(error -> error.errorCode().equals("SQL_EXECUTION_FAILED")));
        assertTrue(export.utf8Content().startsWith("\ufeffSQLTeacher 学情导出"));
        assertTrue(export.utf8Content().contains("通过率=通过提交/全部提交"));
        assertTrue(export.utf8Content().contains("基础查询"));
    }

    private JdbcConnectionFactory initialize() {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(1), "test")
        )).initialize();
        return new JdbcConnectionFactory(databases);
    }

    private static void insertFixture(JdbcConnectionFactory connections) throws Exception {
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used)
                values ('analytics-s1', 'query-01', 1, '2026-07-21T01:00:00Z', 0),
                       ('analytics-s2', 'aggregate-01', 1, '2026-07-20T01:00:00Z', 0)
                """);
            statement.executeUpdate("""
                insert into exercise_attempts(id, session_id, status, sql_text, execution_success, passed,
                    duration_ms, error_code, created_at)
                values ('analytics-a1', 'analytics-s1', 'RUN', 'select 1', 0, null, 10,
                        'SQL_EXECUTION_FAILED', '2026-07-21T01:01:00Z'),
                       ('analytics-a2', 'analytics-s1', 'FAILED', 'select 1', 1, 0, 100,
                        'RESULT_MISMATCH', '2026-07-21T01:02:00Z'),
                       ('analytics-a3', 'analytics-s1', 'PASSED', 'select 1', 1, 1, 200,
                        null, '2026-07-21T01:03:00Z'),
                       ('analytics-a4', 'analytics-s2', 'FAILED', 'select 1', 1, 0, 900,
                        'OLD_ERROR', '2026-07-20T01:02:00Z')
                """);
        }
    }
}
