package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabActivityEvaluatorTest {
    @Test
    void requiresKnownCompletedStepsObservationsAndConclusion() {
        var specification = new LabActivitySpecification(1, "Debug the parser", List.of(
            new LabStep("reproduce", "Reproduce", "Run the fixed input", "actual"),
            new LabStep("verify", "Verify", "Run the regression", "tests")
        ), 20);
        var evaluator = new LabActivityEvaluator();

        var incomplete = evaluator.evaluate(definition(specification), specification,
            new LabActivityArtifact(List.of("reproduce"), Map.of("actual", "wrong output", "tests", ""), "too short"));
        var passed = evaluator.evaluate(definition(specification), specification,
            new LabActivityArtifact(List.of("reproduce", "verify"),
                Map.of("actual", "output differed", "tests", "normal and boundary cases passed"),
                "The defect was caused by tokenization and the regression now passes."));

        assertFalse(incomplete.passed());
        assertEquals("LAB_STEP_INCOMPLETE", incomplete.reasonCode());
        assertTrue(passed.passed());
        assertEquals("LAB_PASSED", passed.reasonCode());
    }

    private static LearningActivityDefinition definition(ActivitySpecification specification) {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        return new LearningActivityDefinition("lab", "course", "section", "Lab", "Lab", List.of("point"),
            ActivityDifficulty.INTERMEDIATE, 30, 1, true, specification, now, now);
    }
}
