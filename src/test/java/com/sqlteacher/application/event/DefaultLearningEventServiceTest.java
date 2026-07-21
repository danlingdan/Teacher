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
}
