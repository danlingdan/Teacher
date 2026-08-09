package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.learning.MasteryLevel;
import com.sqlteacher.application.learning.LearningActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLearningDiagnosisServiceTest {
    @TempDir Path tempDir;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void shouldDiagnoseDeterministicallyAndKeepOwnersIsolated() throws Exception {
        JdbcConnectionFactory connections = initialize();
        insertEvent(connections, 1, "owner-a", "query-01", "EXERCISE_FAILED", false, NOW.minusSeconds(30));
        insertEvent(connections, 2, "owner-a", "query-01", "EXERCISE_FAILED", false, NOW.minusSeconds(20));
        insertEvent(connections, 3, "owner-a", "query-01", "EXERCISE_PASSED", true, NOW.minusSeconds(10));
        insertEvent(connections, 4, "owner-b", "query-01", "EXERCISE_PASSED", true, NOW.minusSeconds(5));
        var service = service(connections, "owner-a");

        var first = service.refresh();
        var second = service.refresh();
        var point = first.mastery().stream().filter(item -> item.knowledgePoint().equals("基础查询"))
            .findFirst().orElseThrow();

        assertEquals(3, point.attempts());
        assertEquals(1, point.passes());
        assertEquals(MasteryLevel.NEEDS_PRACTICE, point.level());
        assertFalse(first.actions().isEmpty());
        assertEquals(first.mastery(), second.mastery());
        assertEquals(first.actions(), second.actions());
    }

    @Test
    void dismissedCycleShouldStayHiddenUntilNewEvidenceArrives() throws Exception {
        JdbcConnectionFactory connections = initialize();
        for (int index = 1; index <= 3; index++) {
            insertEvent(connections, index, "owner-a", "query-01", "EXERCISE_FAILED", false,
                NOW.minusSeconds(10L * index));
        }
        var service = service(connections, "owner-a");
        String id = service.refresh().actions().getFirst().id();

        service.dismissAction(id);
        assertTrue(service.refresh().actions().stream().noneMatch(item -> item.id().equals(id)));

        insertEvent(connections, 9, "owner-a", "query-01", "EXERCISE_FAILED", false, NOW.minusSeconds(1));
        assertTrue(service.refresh().actions().stream().anyMatch(item -> !item.id().equals(id)));
    }

    @Test
    void shouldEscapeSpreadsheetFormulasInCsv() throws Exception {
        JdbcConnectionFactory connections = initialize();
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("update exercises set knowledge_point='=danger' where id='query-01'");
        }
        insertEvent(connections, 1, "owner-a", "query-01", "EXERCISE_PASSED", true, NOW.minusSeconds(1));

        assertTrue(service(connections, "owner-a").exportCsv().contains("\"'=danger\""));
    }

    @Test
    void shouldCombineQuizAndTraceEvidenceAndRecommendTheActivity() throws Exception {
        JdbcConnectionFactory connections = initialize();
        insertActivityEvaluation(connections, "eval-1", "owner-a", "tree-traversal-quiz", "QUIZ",
            false, "QUIZ_BELOW_PASS_SCORE", NOW.minusSeconds(30));
        insertActivityEvaluation(connections, "eval-2", "owner-a", "tree-preorder-trace", "TRACE",
            false, "TRACE_ORDER_INCORRECT", NOW.minusSeconds(20));
        insertActivityEvaluation(connections, "eval-3", "owner-a", "tree-preorder-trace", "TRACE",
            false, "TRACE_ORDER_INCORRECT", NOW.minusSeconds(10));
        insertActivityEvaluation(connections, "other-owner", "owner-b", "tree-preorder-trace", "TRACE",
            true, "TRACE_PASSED", NOW.minusSeconds(5));

        var dashboard = service(connections, "owner-a").refresh();
        var traversal = dashboard.mastery().stream()
            .filter(item -> item.knowledgePoint().equals("二叉树遍历")).findFirst().orElseThrow();

        assertEquals(3, traversal.attempts());
        assertEquals(MasteryLevel.NEEDS_PRACTICE, traversal.level());
        assertTrue(dashboard.actions().stream().anyMatch(action ->
            action.type() == LearningActionType.RETRY_ACTIVITY
                && action.exerciseId().equals("tree-preorder-trace")));
    }

    @Test
    void shouldRequireMoreReadingEvidenceThanExecutableActivityEvidence() throws Exception {
        JdbcConnectionFactory connections = initialize();
        for (int index = 1; index <= 3; index++) {
            insertActivityEvaluation(connections, "reading-" + index, "owner-a", "tree-complexity-reading",
                "READING", true, "READING_RECALL_PASSED", NOW.minusSeconds(10L * index));
        }
        var service = service(connections, "owner-a");
        var initial = service.refresh().mastery().stream()
            .filter(item -> item.knowledgePoint().equals("二叉树遍历")).findFirst().orElseThrow();
        assertEquals(MasteryLevel.UNKNOWN, initial.level());

        for (int index = 4; index <= 5; index++) {
            insertActivityEvaluation(connections, "reading-" + index, "owner-a", "tree-complexity-reading",
                "READING", true, "READING_RECALL_PASSED", NOW.minusSeconds(10L * index));
        }
        var calibrated = service.refresh().mastery().stream()
            .filter(item -> item.knowledgePoint().equals("二叉树遍历")).findFirst().orElseThrow();
        assertEquals(MasteryLevel.MASTERED, calibrated.level());
        assertEquals(5, calibrated.attempts());
    }

    @Test
    void shouldDiagnoseFiveThousandEventsWithinPerformanceBudget() throws Exception {
        JdbcConnectionFactory connections = initialize();
        String attributes = LearningEventAttributesCodec.serialize(Map.of(
            LearningEventOwnerProvider.OWNER_ATTRIBUTE, "owner-a", "exerciseId", "query-01", "status", "FAILED"));
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            insert into learning_events(id,event_type,occurred_at,connection_id,successful,attributes,created_at)
            values(?,?,?,?,?,?,?)
            """)) {
            connection.setAutoCommit(false);
            for (int index = 1; index <= 5_000; index++) {
                Instant at = NOW.minusSeconds(index);
                statement.setInt(1, index); statement.setString(2, index % 4 == 0 ? "EXERCISE_PASSED" : "EXERCISE_FAILED");
                statement.setString(3, at.toString()); statement.setString(4, "exercise");
                statement.setBoolean(5, index % 4 == 0); statement.setString(6, attributes);
                statement.setString(7, at.toString()); statement.addBatch();
            }
            statement.executeBatch(); connection.commit();
        }
        var service = service(connections, "owner-a");

        // Measure steady-state diagnosis rather than one-time JVM/SQLite initialization on shared runners.
        service.refresh();
        long started = System.nanoTime();
        var dashboard = service.refresh();
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertFalse(dashboard.mastery().isEmpty());
        // Keep a bounded regression gate without making shared Windows runners fail on normal I/O jitter.
        assertTrue(millis < 1_500, () -> "diagnosis took " + millis + " ms");
    }

    private JdbcLearningDiagnosisService service(JdbcConnectionFactory connections, String owner) {
        return new JdbcLearningDiagnosisService(connections, () -> owner,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private JdbcConnectionFactory initialize() {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration("SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1),
                Duration.ofSeconds(1), "test"))).initialize();
        return new JdbcConnectionFactory(databases);
    }

    private static void insertEvent(JdbcConnectionFactory connections, long id, String owner, String exerciseId,
                                    String type, boolean successful, Instant occurredAt) throws Exception {
        String attributes = LearningEventAttributesCodec.serialize(Map.of(
            LearningEventOwnerProvider.OWNER_ATTRIBUTE, owner, "exerciseId", exerciseId,
            "status", successful ? "PASSED" : "FAILED"));
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            insert into learning_events(id,event_type,occurred_at,connection_id,successful,attributes,created_at)
            values(?,?,?,?,?,?,?)
            """)) {
            statement.setLong(1, id); statement.setString(2, type); statement.setString(3, occurredAt.toString());
            statement.setString(4, "exercise"); statement.setBoolean(5, successful);
            statement.setString(6, attributes); statement.setString(7, occurredAt.toString());
            statement.executeUpdate();
        }
    }

    private static void insertActivityEvaluation(JdbcConnectionFactory connections, String id, String owner,
                                                 String activityId, String activityType, boolean successful,
                                                 String reasonCode, Instant occurredAt) throws Exception {
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            insert into activity_evaluation_result(
                id,owner_id,activity_id,activity_version,activity_type,status,reason_code,criteria_json,
                evidence_summary_json,evaluator_version,evidence_version,duration_ms,artifact_hash,occurred_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, id); statement.setString(2, owner); statement.setString(3, activityId);
            statement.setInt(4, 1); statement.setString(5, activityType);
            statement.setString(6, successful ? "PASSED" : "FAILED"); statement.setString(7, reasonCode);
            statement.setString(8, "[]"); statement.setString(9, "{}");
            statement.setString(10, "test-v1"); statement.setString(11, "activity-evidence-v2");
            statement.setLong(12, 1); statement.setString(13, id + "-hash");
            statement.setString(14, occurredAt.toString()); statement.executeUpdate();
        }
    }
}
