package com.sqlteacher.application.course;

import java.util.List;
import java.util.Objects;

public record CourseMapSnapshot(List<CourseMapCourse> courses) {
    public CourseMapSnapshot {
        courses = List.copyOf(Objects.requireNonNull(courses, "courses must not be null"));
    }

    public int activityCount() {
        return courses.stream().flatMap(course -> course.sections().stream())
            .mapToInt(section -> section.activities().size()).sum();
    }
}
