package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapCourse;
import com.sqlteacher.application.course.CourseMapSection;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.ActivityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityWorkspaceControllerTest {
    @Test
    void shouldKeepCoursesSeparateAndHideDisabledActivities() {
        var database = course("database", List.of(activity("sql", true), activity("disabled", false)));
        var programming = course("programming", List.of(activity("code", true)));
        var empty = course("empty", List.of(activity("hidden", false)));

        var courses = ActivityWorkspaceController.coursesWithEnabledActivities(
            new CourseMapSnapshot(List.of(database, programming, empty))
        );

        assertEquals(List.of(database, programming), courses);
        assertEquals(List.of("sql"), ActivityWorkspaceController.enabledActivities(database).stream()
            .map(CourseMapActivity::id).toList());
        assertEquals(List.of("code"), ActivityWorkspaceController.enabledActivities(programming).stream()
            .map(CourseMapActivity::id).toList());
    }

    private static CourseMapCourse course(String id, List<CourseMapActivity> activities) {
        return new CourseMapCourse(id, id, "1", List.of(new CourseMapSection(id + "-section", id, 0, activities)));
    }

    private static CourseMapActivity activity(String id, boolean enabled) {
        return new CourseMapActivity(id, id, ActivityType.SQL, ActivityDifficulty.BEGINNER, 10, enabled, List.of());
    }
}
