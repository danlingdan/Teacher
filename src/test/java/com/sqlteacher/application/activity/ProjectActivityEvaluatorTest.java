package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.ProjectActivitySpecification;
import com.sqlteacher.domain.activity.ProjectMilestone;
import com.sqlteacher.domain.activity.ProjectRubricCriterion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectActivityEvaluatorTest {
    @Test
    void shouldRequireEveryMilestoneEvidenceAndReflectionBeforeTeacherReview() {
        var specification = specification();
        var evaluator = new ProjectActivityEvaluator();

        var incomplete = evaluator.evaluate(definition(specification), specification,
            new ProjectActivityArtifact(1, List.of("scope"), "short", "short"));
        var ready = evaluator.evaluate(definition(specification), specification,
            new ProjectActivityArtifact(2, List.of("scope", "delivery"), "1234567890", "12345678"));

        assertFalse(incomplete.passed());
        assertEquals("PROJECT_MILESTONE_INCOMPLETE", incomplete.reasonCode());
        assertTrue(ready.passed());
        assertEquals("PROJECT_READY_FOR_TEACHER_REVIEW", ready.reasonCode());
        assertTrue(ready.summary().contains("教师量规评审"));
    }

    @Test
    void shouldRejectUnknownMilestonesAndInvalidRubricWeights() {
        var evaluator = new ProjectActivityEvaluator();
        var specification = specification();
        var result = evaluator.evaluate(definition(specification), specification,
            new ProjectActivityArtifact(1, List.of("unknown"), "1234567890", "12345678"));
        assertEquals("PROJECT_MILESTONE_UNKNOWN", result.reasonCode());
        assertThrows(IllegalArgumentException.class, () -> new ProjectActivitySpecification(1, "project",
            List.of(new ProjectMilestone("scope", "Scope", "Accepted")),
            List.of(new ProjectRubricCriterion("quality", "Quality", 99)), 10, 8));
    }

    private static ProjectActivitySpecification specification() {
        return new ProjectActivitySpecification(1, "project",
            List.of(new ProjectMilestone("scope", "Scope", "Accepted"),
                new ProjectMilestone("delivery", "Delivery", "Checks pass")),
            List.of(new ProjectRubricCriterion("quality", "Quality", 60),
                new ProjectRubricCriterion("reflection", "Reflection", 40)), 10, 8);
    }

    private static LearningActivityDefinition definition(ProjectActivitySpecification specification) {
        return new LearningActivityDefinition("project", "course", "section", "Project", "Project",
            List.of("knowledge"), ActivityDifficulty.INTERMEDIATE, 60, 1, true, specification,
            Instant.EPOCH, Instant.EPOCH);
    }
}
