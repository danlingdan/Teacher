package com.sqlteacher.application.planning;

import com.sqlteacher.application.collaboration.ContentStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicStudyPlanCapacityTest {
    @Test
    void shouldPlanTenThousandObjectiveFactsWithinBudget() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        List<CourseObjective> objectives = new ArrayList<>(10_000);
        List<ObjectiveResourceLink> resources = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            String id = "objective-" + index;
            objectives.add(new CourseObjective(id, "course", "目标 " + index, "", "完成练习", index,
                ContentStatus.ACTIVE, 1, "teacher", now, now));
            resources.add(new ObjectiveResourceLink(id, ObjectiveResourceType.KNOWLEDGE_POINT,
                "point-" + index, now));
        }
        DeterministicStudyPlanService service = new DeterministicStudyPlanService();
        service.generate("student", "course", objectives.subList(0, 100), List.of(),
            resources.subList(0, 100), Set.of(), now);

        long started = System.nanoTime();
        StudyPlanSnapshot plan = service.generate("student", "course", objectives, List.of(), resources,
            Set.of(), now);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(7, plan.actions().size());
        assertTrue(elapsedMillis < 750, "10,000-fact plan took " + elapsedMillis + " ms");
    }
}
