package com.sqlteacher.application.event;

import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLearningEventServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T08:00:00Z");
    private final List<LearningEvent> recorded = new ArrayList<>();
    private final LearningEventService service = new DefaultLearningEventService(
        recorded::add,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void shouldRecordSqlExecutionWithoutRawSql() {
        service.recordSqlExecution("demo", true, "SELECT", Duration.ofMillis(18), 2, null);

        LearningEvent event = recorded.getFirst();
        assertEquals(LearningEventType.SQL_EXECUTION, event.type());
        assertEquals(NOW, event.occurredAt());
        assertTrue(event.successful());
        assertEquals("18", event.attributes().get("durationMs"));
        assertEquals("2", event.attributes().get("resultCount"));
        assertEquals(LearningEventOwnerProvider.GUEST_OWNER, event.attributes().get(LearningEventOwnerProvider.OWNER_ATTRIBUTE));
        assertFalse(event.attributes().containsKey("sql"));
        assertFalse(event.attributes().containsKey("errorCode"));
    }

    @Test
    void shouldRecordRiskBlockWithoutReasonOrSqlText() {
        service.recordSqlRiskBlocked("demo", "DROP", SqlRiskLevel.FORBIDDEN, true);

        LearningEvent event = recorded.getFirst();
        assertEquals(LearningEventType.SQL_RISK_BLOCKED, event.type());
        assertFalse(event.successful());
        assertEquals("FORBIDDEN", event.attributes().get("riskLevel"));
        assertEquals("true", event.attributes().get("multiStatement"));
        assertEquals(4, event.attributes().size());
    }

    @Test
    void shouldUseSeparateAiSuccessAndFailureTypes() {
        service.recordAiGeneration("demo", true, "qwen", "p0-v1", null);
        service.recordAiGeneration("demo", false, "qwen", "p0-v1", "AI_INVALID_JSON");

        assertEquals(LearningEventType.AI_SQL_GENERATED, recorded.get(0).type());
        assertEquals(LearningEventType.AI_GENERATION_FAILED, recorded.get(1).type());
        assertEquals("AI_INVALID_JSON", recorded.get(1).attributes().get("errorCode"));
    }

    @Test
    void shouldRejectInvalidEventDetailsBeforeCallingRecorder() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.recordSqlExecution("demo", true, "SELECT", Duration.ZERO, -1, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.recordAiGeneration(" ", false, "qwen", "p0-v1", "AI_UNAVAILABLE")
        );
        assertTrue(recorded.isEmpty());
    }

    @Test
    void shouldRecordExerciseAndKnowledgeMetricsWithoutSensitiveContent() {
        service.recordExerciseAttempt("query-01", "PASSED", true, Duration.ofMillis(25), null);
        service.recordExerciseHint("query-01", 2);
        service.recordKnowledgeSearch(12, 3);

        assertEquals(LearningEventType.EXERCISE_PASSED, recorded.get(0).type());
        assertEquals("query-01", recorded.get(0).attributes().get("exerciseId"));
        assertEquals("query-01", recorded.get(0).attributes().get("activityId"));
        assertEquals("SQL", recorded.get(0).attributes().get("activityType"));
        assertEquals("sql-deterministic-v1", recorded.get(0).attributes().get("evaluatorVersion"));
        assertEquals("activity-evidence-v1", recorded.get(0).attributes().get("evidenceVersion"));
        assertFalse(recorded.get(0).attributes().containsKey("sql"));
        assertEquals(LearningEventType.EXERCISE_HINT_USED, recorded.get(1).type());
        assertEquals("2", recorded.get(1).attributes().get("hintLevel"));
        assertEquals(LearningEventType.KNOWLEDGE_SEARCHED, recorded.get(2).type());
        assertEquals("12", recorded.get(2).attributes().get("queryLength"));
        assertFalse(recorded.get(2).attributes().containsKey("query"));
    }

    @Test
    void shouldTagEventsWithAuthenticatedOwner() {
        var authenticatedService = new DefaultLearningEventService(
            recorded::add,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "user-1"
        );

        authenticatedService.recordKnowledgeSearch(4, 1);

        assertEquals("user-1", recorded.getFirst().attributes().get(LearningEventOwnerProvider.OWNER_ATTRIBUTE));
    }

    @Test
    void shouldRecordSqlTextDialectAndHashOnRichExecutionEvidence() {
        service.recordSqlExecution("demo", true, "SELECT", Duration.ofMillis(18), 2, null,
            "SELECT * FROM students", "sqlite");

        LearningEvent event = recorded.getFirst();
        assertEquals("SELECT * FROM students", event.attributes().get("sqlText"));
        assertEquals("sqlite", event.attributes().get("dialect"));
        assertEquals(64, event.attributes().get("sqlHash").length());
        assertTrue(event.attributes().get("sqlHash").matches("[0-9a-f]+"));
    }

    @Test
    void shouldHashSqlDeterministicallyForAiExecutionCorrelation() {
        service.recordAiGeneration("demo", true, "qwen", "p0-v1", null, "SELECT 1");
        service.recordSqlExecution("demo", true, "SELECT", Duration.ofMillis(5), 1, null, "SELECT 1", "sqlite");
        service.recordSqlExecution("demo", true, "SELECT", Duration.ofMillis(5), 1, null, "SELECT 2", "sqlite");

        String draftHash = recorded.get(0).attributes().get("sqlHash");
        assertEquals(draftHash, recorded.get(1).attributes().get("sqlHash"));
        assertEquals("SELECT 1", recorded.get(0).attributes().get("sqlText"));
        assertNotEquals(draftHash, recorded.get(2).attributes().get("sqlHash"));
    }

    @Test
    void shouldRecordScoreAndSubmittedSqlOnExerciseSubmissions() {
        service.recordExerciseAttempt("query-01", "PASSED", true, Duration.ofMillis(25), null, 88, "SELECT 1");
        service.recordExerciseAttempt("query-02", "FAILED", false, Duration.ofMillis(40), "RESULT_MISMATCH", 30, null);

        assertEquals("88", recorded.get(0).attributes().get("score"));
        assertEquals("SELECT 1", recorded.get(0).attributes().get("sqlText"));
        assertEquals("RESULT_MISMATCH", recorded.get(1).attributes().get("errorCode"));
        assertFalse(recorded.get(1).attributes().containsKey("sqlText"));
    }

    @Test
    void shouldKeepLegacySignaturesFreeOfSqlEvidence() {
        service.recordSqlExecution("demo", true, "SELECT", Duration.ofMillis(18), 2, null);
        service.recordAiGeneration("demo", true, "qwen", "p0-v1", null);

        assertFalse(recorded.get(0).attributes().containsKey("sqlText"));
        assertFalse(recorded.get(1).attributes().containsKey("sqlText"));
    }

    @Test
    void shouldRecordSearchPreviewOnlyWhenProvided() {
        service.recordKnowledgeSearch(12, 3, "关系模型");
        service.recordKnowledgeSearch(12, 3);

        assertEquals("关系模型", recorded.get(0).attributes().get("queryPreview"));
        assertFalse(recorded.get(1).attributes().containsKey("queryPreview"));
    }

    @Test
    void shouldRecordNewEvidenceEventTypes() {
        service.recordMasteryChanged("WHERE 过滤", "DEVELOPING", 55, 4, 2, 2);
        service.recordDailyActive(30);
        service.recordKnowledgeArticleRead("a-1", 3, 75);
        service.recordAssistantAsked("什么是关系模型？", 5, "ANSWERED");

        assertEquals(LearningEventType.MASTERY_CHANGED, recorded.get(0).type());
        assertEquals("55", recorded.get(0).attributes().get("masteryPercent"));
        assertEquals(LearningEventType.DAILY_ACTIVE, recorded.get(1).type());
        assertEquals("30", recorded.get(1).attributes().get("activeMinutes"));
        assertEquals(LearningEventType.KNOWLEDGE_ARTICLE_READ, recorded.get(2).type());
        assertEquals("75", recorded.get(2).attributes().get("progressPercent"));
        assertEquals(LearningEventType.AI_ASSISTANT_ASKED, recorded.get(3).type());
        assertEquals("什么是关系模型？", recorded.get(3).attributes().get("questionPreview"));
        assertThrows(IllegalArgumentException.class, () -> service.recordDailyActive(0));
        assertThrows(IllegalArgumentException.class, () -> service.recordMasteryChanged("p", "LOW", 101, 0, 0, 0));
    }
}
