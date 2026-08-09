package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.SimulationAction;
import com.sqlteacher.domain.activity.SimulationActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivitySpecification;
import com.sqlteacher.domain.activity.SimulationCheckpoint;
import com.sqlteacher.domain.activity.SimulationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationActivityEvaluatorTest {
    @Test
    void shouldReplayActionsAndEvaluateEveryCheckpointDeterministically() {
        var evaluator = new SimulationActivityEvaluator();
        var specification = specification();

        var incomplete = evaluator.evaluate(definition(specification), specification,
            new SimulationActivityArtifact(List.of("fetch")));
        var passed = evaluator.evaluate(definition(specification), specification,
            new SimulationActivityArtifact(List.of("fetch", "execute")));

        assertFalse(incomplete.passed());
        assertEquals("EXECUTE_NOT_REACHED", incomplete.reasonCode());
        assertEquals(1, incomplete.criteria().stream().filter(ActivityCriterionResult::passed)
            .filter(item -> item.criterion().startsWith("checkpoint-")).count());
        assertTrue(passed.passed());
        assertEquals("SIMULATION_PASSED", passed.reasonCode());
        assertEquals(SimulationActivityEvaluator.VERSION, passed.evaluatorVersion());
    }

    @Test
    void shouldRejectUnknownAndOutOfOrderActionsWithStableReasons() {
        var evaluator = new SimulationActivityEvaluator();
        var specification = specification();

        var unknown = evaluator.evaluate(definition(specification), specification,
            new SimulationActivityArtifact(List.of("unknown")));
        var outOfOrder = evaluator.evaluate(definition(specification), specification,
            new SimulationActivityArtifact(List.of("execute")));

        assertEquals("SIMULATION_ACTION_UNKNOWN", unknown.reasonCode());
        assertEquals("SIMULATION_TRANSITION_INVALID", outOfOrder.reasonCode());
    }

    @Test
    void shouldRejectUnreachableCourseDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new SimulationActivitySpecification(
            1, "prompt", "ready", "done",
            List.of(state("ready"), state("done"), state("orphan")),
            List.of(new SimulationAction("finish", "Finish", "ready", "done", "done")),
            List.of(new SimulationCheckpoint("orphan", "orphan", "Orphan", "Reached", "ORPHAN_NOT_REACHED"))
        ));
    }

    private static SimulationActivitySpecification specification() {
        return new SimulationActivitySpecification(
            1, "Complete the cycle", "ready", "done",
            List.of(state("ready"), state("fetched"), state("done")),
            List.of(
                new SimulationAction("fetch", "Fetch", "ready", "fetched", "instruction fetched"),
                new SimulationAction("execute", "Execute", "fetched", "done", "instruction executed")
            ),
            List.of(
                new SimulationCheckpoint("fetch", "fetched", "Fetch", "Fetch reached", "FETCH_NOT_REACHED"),
                new SimulationCheckpoint("execute", "done", "Execute", "Execute reached", "EXECUTE_NOT_REACHED")
            )
        );
    }

    private static SimulationState state(String id) {
        return new SimulationState(id, id, id + " description", List.of(id + " observation"));
    }

    private static LearningActivityDefinition definition(SimulationActivitySpecification specification) {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");
        return new LearningActivityDefinition("simulation", "course", "section", "Simulation", "Simulation",
            List.of("knowledge"), ActivityDifficulty.BEGINNER, 10, 1, true, specification, now, now);
    }
}
