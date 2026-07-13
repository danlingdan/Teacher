package com.sqlteacher.application;

import com.sqlteacher.application.event.LearningEvent;
import com.sqlteacher.application.event.LearningEventType;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationContractTest {
    @Test
    void shouldDefensivelyCopyExecutionCollections() {
        List<String> columns = new ArrayList<>(List.of("name"));
        Map<String, Object> row = new HashMap<>(Map.of("name", "Alice"));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row));

        SqlExecutionResult result = new SqlExecutionResult(
            true, columns, rows, 0, false, "ok", Duration.ofMillis(10)
        );
        columns.add("score");
        row.put("name", "changed");
        rows.clear();

        assertEquals(List.of("name"), result.columns());
        assertEquals("Alice", result.rows().getFirst().get("name"));
        assertThrows(UnsupportedOperationException.class, () -> result.rows().add(Map.of()));
    }

    @Test
    void shouldExposeImmutableRiskReasonsAndEventAttributes() {
        SqlRiskAnalysis risk = new SqlRiskAnalysis(
            SqlRiskLevel.FORBIDDEN, false, false, true, "DROP", List.of("forbidden")
        );
        LearningEvent event = new LearningEvent(
            LearningEventType.SQL_RISK_BLOCKED,
            Instant.EPOCH,
            "demo",
            false,
            Map.of("level", risk.level().name())
        );

        assertThrows(UnsupportedOperationException.class, () -> risk.reasons().add("another"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> event.attributes().put("sql", "DROP DATABASE demo")
        );
    }

    @Test
    void shouldValidateRequiredLearningEventFields() {
        assertThrows(
            NullPointerException.class,
            () -> new LearningEvent(null, Instant.EPOCH, "demo", true, Map.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new LearningEvent(LearningEventType.SQL_EXECUTION, Instant.EPOCH, " ", true, Map.of())
        );
        assertThrows(
            NullPointerException.class,
            () -> new LearningEvent(
                LearningEventType.SQL_EXECUTION,
                Instant.EPOCH,
                "demo",
                true,
                null
            )
        );
    }
}
