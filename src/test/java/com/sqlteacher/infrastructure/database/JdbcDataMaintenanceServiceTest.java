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
            statement.executeUpdate("insert into study_plan_snapshot(id,owner_id,course_id,policy_version,fact_watermark,generated_at,expires_at,status) values('plan-1','guest','course','v1','facts','2026-08-01T00:00:00Z','2026-08-08T00:00:00Z','ACTIVE')");
            statement.executeUpdate("insert into study_plan_action(id,snapshot_id,action_key,objective_id,action_type,title,description,resource_type,resource_id,reason_code,resolution_condition,priority,state,sync_version,updated_at) values('row-1','plan-1','action-1','objective','REVIEW_KNOWLEDGE','复习','说明','KNOWLEDGE_POINT','point','INSUFFICIENT_EVIDENCE','产生证据',50,'OPEN',0,'2026-08-01T00:00:00Z')");
            statement.executeUpdate("insert into grounded_tutor_session(id,owner_id,course_id,objective_id,retrieval_snapshot_hash,provider,model,result_code,degraded,created_at) values('tutor-1','guest','course','objective','hash','DETERMINISTIC','','DEGRADED',1,'2026-08-01T00:00:00Z')");
        }

        LearningDataResetResult result = new JdbcDataMaintenanceService(connections).resetLearningData();

        assertEquals(new LearningDataResetResult(1, 1, 1), result);
        assertEquals(0, count(connections, "exercise_sessions"));
        assertEquals(0, count(connections, "exercise_attempts"));
        assertEquals(20, count(connections, "exercises"));
        assertEquals(0, count(connections, "study_plan_snapshot"));
        assertEquals(0, count(connections, "grounded_tutor_session"));
    }

    private static int count(JdbcConnectionFactory connections, String table) throws Exception {
        if (!java.util.Set.of("exercise_sessions", "exercise_attempts", "exercises", "study_plan_snapshot",
            "grounded_tutor_session").contains(table)) {
            throw new IllegalArgumentException("Unexpected table");
        }
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
