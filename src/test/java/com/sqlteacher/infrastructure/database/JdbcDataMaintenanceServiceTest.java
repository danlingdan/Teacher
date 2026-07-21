package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.maintenance.LearningDataResetResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcDataMaintenanceServiceTest {
    @TempDir Path tempDir;

    @Test
    void shouldResetLearningRecordsButKeepCatalogAndKnowledge() throws Exception {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(1), "test")
        )).initialize();
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used) values ('reset-s1', 'query-01', 1, '2026-07-21T01:00:00Z', 0)");
            statement.executeUpdate("insert into exercise_attempts(id, session_id, status, sql_text, execution_success, passed, duration_ms, created_at) values ('reset-a1', 'reset-s1', 'RUN', 'select 1', 1, null, 1, '2026-07-21T01:01:00Z')");
            statement.executeUpdate("insert into learning_events(event_type, occurred_at, connection_id, successful) values ('EXERCISE_ATTEMPT', '2026-07-21T01:01:00Z', 'exercise', 1)");
        }

        LearningDataResetResult result = new JdbcDataMaintenanceService(connections).resetLearningData();

        assertEquals(new LearningDataResetResult(1, 1, 1), result);
        assertEquals(0, count(connections, "exercise_sessions"));
        assertEquals(0, count(connections, "exercise_attempts"));
        assertEquals(20, count(connections, "exercises"));
    }

    private static int count(JdbcConnectionFactory connections, String table) throws Exception {
        if (!java.util.Set.of("exercise_sessions", "exercise_attempts", "exercises").contains(table)) {
            throw new IllegalArgumentException("Unexpected table");
        }
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
