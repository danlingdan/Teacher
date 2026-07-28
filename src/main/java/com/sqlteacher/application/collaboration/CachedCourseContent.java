package com.sqlteacher.application.collaboration;

import java.util.List;

public record CachedCourseContent(List<CourseSection> sections, List<KnowledgePoint> knowledgePoints,
                                  List<SharedExerciseVersion> exercises) {
    public CachedCourseContent {
        sections = sections == null ? List.of() : List.copyOf(sections);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        exercises = exercises == null ? List.of() : List.copyOf(exercises);
    }
}
