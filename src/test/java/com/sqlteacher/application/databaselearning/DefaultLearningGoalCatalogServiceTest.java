package com.sqlteacher.application.databaselearning;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapCourse;
import com.sqlteacher.application.course.CourseMapSection;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.ActivityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultLearningGoalCatalogServiceTest {
    @Test
    void shouldOnlyUseActivitiesFromTheDatabaseCourse() {
        var database = course(DefaultLearningGoalCatalogService.DATABASE_COURSE_ID, "数据管理", List.of(
            activity("sql-basic", "查询学生", ActivityType.SQL, ActivityDifficulty.BEGINNER, true),
            activity("sql-advanced", "复杂查询", ActivityType.SQL, ActivityDifficulty.ADVANCED, true),
            activity("sql-disabled", "未启用查询", ActivityType.SQL, ActivityDifficulty.BEGINNER, false)
        ));
        var programming = course("builtin-programming-basics", "编程语言基础", List.of(
            activity("code-java", "Java 两数求和", ActivityType.CODE, ActivityDifficulty.BEGINNER, true)
        ));
        var service = new DefaultLearningGoalCatalogService(() -> new CourseMapSnapshot(List.of(database, programming)));

        Set<String> titles = service.load().stream()
            .flatMap(goal -> goal.stages().stream())
            .flatMap(stage -> stage.activities().stream())
            .map(CourseMapActivity::title)
            .collect(Collectors.toSet());

        assertEquals(Set.of("查询学生", "复杂查询"), titles);
    }

    private static CourseMapCourse course(String id, String title, List<CourseMapActivity> activities) {
        return new CourseMapCourse(id, title, "1", List.of(new CourseMapSection(id + "-section", title, 0, activities)));
    }

    private static CourseMapActivity activity(String id, String title, ActivityType type,
                                               ActivityDifficulty difficulty, boolean enabled) {
        return new CourseMapActivity(id, title, type, difficulty, 10, enabled, List.of("知识点"));
    }
}
