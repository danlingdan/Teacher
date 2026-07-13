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
        assertEquals(3, event.attributes().size());
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
}
