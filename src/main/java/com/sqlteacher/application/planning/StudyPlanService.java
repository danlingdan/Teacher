package com.sqlteacher.application.planning;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface StudyPlanService {
    StudyPlanSnapshot generate(String ownerId, String courseId, List<CourseObjective> objectives,
                               List<ObjectivePrerequisite> prerequisites,
                               List<ObjectiveResourceLink> resources,
                               Set<String> completedObjectiveIds, Instant generatedAt);
}
