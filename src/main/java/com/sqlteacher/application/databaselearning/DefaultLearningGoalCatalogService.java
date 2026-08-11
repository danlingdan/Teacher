package com.sqlteacher.application.databaselearning;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.domain.activity.ActivityDifficulty;

import java.util.List;
import java.util.Objects;

public final class DefaultLearningGoalCatalogService implements LearningGoalCatalogService {
    static final String DATABASE_COURSE_ID = "builtin-data-management";
    private final CourseMapService courseMapService;

    public DefaultLearningGoalCatalogService(CourseMapService courseMapService) {
        this.courseMapService = Objects.requireNonNull(courseMapService);
    }

    @Override
    public List<LearningGoal> load() {
        List<CourseMapActivity> activities = courseMapService.load().courses().stream()
            .filter(course -> DATABASE_COURSE_ID.equals(course.id()))
            .flatMap(course -> course.sections().stream())
            .flatMap(section -> section.activities().stream())
            .filter(CourseMapActivity::enabled)
            .toList();
        List<CourseMapActivity> beginner = byDifficulty(activities, ActivityDifficulty.BEGINNER);
        List<CourseMapActivity> intermediate = byDifficulty(activities, ActivityDifficulty.INTERMEDIATE);
        List<CourseMapActivity> advanced = byDifficulty(activities, ActivityDifficulty.ADVANCED);
        return List.of(
            goal("sql-application", "SQL 应用开发", "能从业务问题构造、检查并解释查询。", beginner, intermediate, advanced),
            goal("database-design", "数据库设计", "能把需求拆成实体、关系、约束和可审查 DDL。", beginner, intermediate, advanced),
            goal("data-preparation", "公开数据整理", "能安全预览公开表格并构造有界导入草稿。", beginner, intermediate, advanced)
        );
    }

    private static LearningGoal goal(String id, String title, String outcome,
                                     List<CourseMapActivity> beginner,
                                     List<CourseMapActivity> intermediate,
                                     List<CourseMapActivity> advanced) {
        return new LearningGoal(id, title, outcome, List.of(
            new LearningStage("理解任务", "先阅读要求并识别需要的表、字段与约束。", beginner),
            new LearningStage("完成实验", "在本地实验工作区编写并检查草稿。", intermediate),
            new LearningStage("解释与改进", "根据分项反馈修订方案并说明取舍。", advanced)
        ));
    }

    private static List<CourseMapActivity> byDifficulty(List<CourseMapActivity> activities, ActivityDifficulty difficulty) {
        return activities.stream().filter(activity -> activity.difficulty() == difficulty).toList();
    }
}
