package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingActivityEvaluatorTest {
    @Test
    void readingCompletionAloneIsNotMasteryEvidence() {
        var specification = specification(100);
        var result = new ReadingActivityEvaluator().evaluate(definition(specification), specification,
            new ReadingActivityArtifact(true, Map.of()));

        assertFalse(result.passed());
        assertEquals("READING_RECALL_INCOMPLETE", result.reasonCode());
    }

    @Test
    void normalizesRecallAnswersAndSupportsAnExplicitOverallThreshold() {
        var specification = specification(50);
        var result = new ReadingActivityEvaluator().evaluate(definition(specification), specification,
            new ReadingActivityArtifact(true, Map.of("order", " ROOT   LEFT RIGHT ", "time", "wrong")));

        assertTrue(result.passed());
        assertEquals("READING_RECALL_PASSED", result.reasonCode());
        assertEquals(1, result.criteria().stream().filter(item -> !item.passed()).count());
    }

    private static ReadingActivitySpecification specification(int passPercent) {
        return new ReadingActivitySpecification(1, "Original note", "Apache-2.0", "A sufficiently long original reading body.",
            List.of(new ReadingCheck("order", "Order?", "root left right", "Use root, left, right."),
                new ReadingCheck("time", "Time?", "O(n)", "Each node is visited once.")), passPercent);
    }

    private static LearningActivityDefinition definition(ActivitySpecification specification) {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        return new LearningActivityDefinition("reading", "course", "section", "Reading", "Reading",
            List.of("point"), ActivityDifficulty.BEGINNER, 8, 1, true, specification, now, now);
    }
}
