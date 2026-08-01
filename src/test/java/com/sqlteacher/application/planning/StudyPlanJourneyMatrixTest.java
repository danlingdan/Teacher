package com.sqlteacher.application.planning;

import com.sqlteacher.application.collaboration.ContentStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyPlanJourneyMatrixTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @TestFactory
    Stream<DynamicTest> shouldKeepThirtyFixedLearningJourneysDeterministicAndBounded() {
        return IntStream.range(0, 30).mapToObj(index -> DynamicTest.dynamicTest("journey-" + (index + 1), () -> {
            int objectiveCount = 3 + index % 10;
            List<CourseObjective> objectives = new ArrayList<>();
            List<ObjectiveResourceLink> resources = new ArrayList<>();
            List<ObjectivePrerequisite> prerequisites = new ArrayList<>();
            for (int item = 0; item < objectiveCount; item++) {
                String id = "j" + index + "-o" + item;
                objectives.add(new CourseObjective(id, "course", "目标 " + item, "", "完成关联动作", item,
                    ContentStatus.ACTIVE, 1, "teacher", NOW, NOW));
                ObjectiveResourceType type = item % 3 == 0 ? ObjectiveResourceType.EXERCISE_VERSION
                    : item % 3 == 1 ? ObjectiveResourceType.KNOWLEDGE_POINT : ObjectiveResourceType.KNOWLEDGE_ARTICLE;
                resources.add(new ObjectiveResourceLink(id, type, "resource-" + index + '-' + item, NOW));
                if (item > 0 && (index + item) % 3 == 0) {
                    prerequisites.add(new ObjectivePrerequisite(id, "j" + index + "-o" + (item - 1), NOW));
                }
            }
            Set<String> completed = index % 4 == 0 ? Set.of("j" + index + "-o0") : Set.of();
            DeterministicStudyPlanService service = new DeterministicStudyPlanService();
            StudyPlanSnapshot first = service.generate("student", "course", objectives, prerequisites, resources,
                completed, NOW);
            StudyPlanSnapshot repeated = service.generate("student", "course", objectives.reversed(),
                prerequisites.reversed(), resources.reversed(), completed, NOW);

            assertEquals(first, repeated);
            assertTrue(first.actions().size() <= 7);
            assertTrue(first.actions().stream().noneMatch(action -> completed.contains(action.objectiveId())));
            assertTrue(first.actions().stream().allMatch(action -> action.priority() >= 1 && action.priority() <= 100));
        }));
    }
}
