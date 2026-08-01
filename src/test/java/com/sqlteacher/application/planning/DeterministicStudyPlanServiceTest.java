package com.sqlteacher.application.planning;

import com.sqlteacher.application.collaboration.ContentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicStudyPlanServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private final DeterministicStudyPlanService service = new DeterministicStudyPlanService();

    @Test
    void shouldPrioritizeAnUnmetPrerequisiteAndRemainStable() {
        CourseObjective basics = objective("basics", 1);
        CourseObjective advanced = objective("advanced", 2);
        List<ObjectiveResourceLink> resources = List.of(resource("basics", "article-basics"),
            resource("advanced", "article-advanced"));
        ObjectivePrerequisite prerequisite = new ObjectivePrerequisite("advanced", "basics", NOW);

        StudyPlanSnapshot first = service.generate("student", "course", List.of(basics, advanced),
            List.of(prerequisite), resources, Set.of(), NOW);
        StudyPlanSnapshot second = service.generate("student", "course", List.of(advanced, basics),
            List.of(prerequisite), resources.reversed(), Set.of(), NOW);

        assertEquals(first, second);
        assertEquals("basics", first.actions().getFirst().objectiveId());
        assertEquals(StudyPlanReasonCode.PREREQUISITE_GAP, first.actions().getFirst().reasonCode());
        assertEquals(90, first.actions().getFirst().priority());
    }

    @Test
    void shouldCapActionsAtSevenAndIgnoreCompletedOrUnresourcedObjectives() {
        List<CourseObjective> objectives = new ArrayList<>();
        List<ObjectiveResourceLink> resources = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            objectives.add(objective("objective-" + index, index));
            if (index != 8) resources.add(resource("objective-" + index, "article-" + index));
        }

        StudyPlanSnapshot plan = service.generate("student", "course", objectives, List.of(), resources,
            Set.of("objective-0"), NOW);

        assertEquals(7, plan.actions().size());
        assertTrue(plan.actions().stream().noneMatch(action -> action.objectiveId().equals("objective-0")));
        assertTrue(plan.actions().stream().noneMatch(action -> action.objectiveId().equals("objective-8")));
        assertTrue(plan.actions().stream().allMatch(action -> action.reasonCode()
            == StudyPlanReasonCode.INSUFFICIENT_EVIDENCE));
    }

    private CourseObjective objective(String id, int sortOrder) {
        return new CourseObjective(id, "course", "目标 " + id, "", "完成关联学习", sortOrder,
            ContentStatus.ACTIVE, 1, "teacher", NOW, NOW);
    }

    private ObjectiveResourceLink resource(String objectiveId, String resourceId) {
        return new ObjectiveResourceLink(objectiveId, ObjectiveResourceType.KNOWLEDGE_ARTICLE, resourceId, NOW);
    }
}
