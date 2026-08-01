package com.sqlteacher.application.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeRetrievalEvaluatorTest {
    @Test
    void calculatesOfflineRagMetricsDeterministically() {
        var report = new KnowledgeRetrievalEvaluator().evaluate(List.of(
            new KnowledgeRetrievalEvaluator.EvaluationCase(Set.of("a"), List.of("x", "a"), 2, 2, 20),
            new KnowledgeRetrievalEvaluator.EvaluationCase(Set.of(), List.of(), 0, 0, 80)
        ));
        assertEquals(0.5, report.recallAtK());
        assertEquals(0.25, report.meanReciprocalRank());
        assertEquals(1.0, report.noAnswerPrecision());
        assertEquals(1.0, report.citationCoverage());
        assertEquals(80, report.latencyP95Millis());
    }
}
