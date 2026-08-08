package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapCourse;
import com.sqlteacher.application.course.CourseMapSection;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.ActivityType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcCourseMapService implements CourseMapService {
    private final JdbcConnectionFactory connectionFactory;

    public JdbcCourseMapService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
    }

    @Override
    public CourseMapSnapshot load() {
        String sql = """
            select c.id course_id, c.title course_title, c.version course_version,
                s.id section_id, s.title section_title, s.sort_order,
                a.id activity_id, a.title activity_title, a.activity_type,
                a.difficulty, a.estimated_minutes, a.enabled,
                kp.name knowledge_point
            from course_definition c
            left join course_section s on s.course_id = c.id
            left join learning_activity_definition a on a.section_id = s.id
            left join activity_knowledge_point ak on ak.activity_id = a.id
            left join knowledge_point_definition kp on kp.id = ak.knowledge_point_id
            where c.visibility = 'PUBLISHED'
            order by c.title, s.sort_order, a.title, kp.name
            """;
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            Map<String, CourseBuilder> courses = new LinkedHashMap<>();
            while (rows.next()) {
                CourseBuilder course = courses.computeIfAbsent(rows.getString("course_id"), ignored ->
                    new CourseBuilder(
                        rowsUnchecked(rows, "course_id"), rowsUnchecked(rows, "course_title"),
                        rowsUnchecked(rows, "course_version")
                    )
                );
                String sectionId = rows.getString("section_id");
                if (sectionId == null) continue;
                SectionBuilder section = course.sections.computeIfAbsent(sectionId, ignored ->
                    new SectionBuilder(sectionId, rowsUnchecked(rows, "section_title"), intUnchecked(rows, "sort_order"))
                );
                String activityId = rows.getString("activity_id");
                if (activityId == null) continue;
                ActivityBuilder activity = section.activities.computeIfAbsent(activityId, ignored ->
                    new ActivityBuilder(
                        activityId,
                        rowsUnchecked(rows, "activity_title"),
                        ActivityType.valueOf(rowsUnchecked(rows, "activity_type")),
                        ActivityDifficulty.valueOf(rowsUnchecked(rows, "difficulty")),
                        intUnchecked(rows, "estimated_minutes"),
                        intUnchecked(rows, "enabled") == 1
                    )
                );
                String knowledgePoint = rows.getString("knowledge_point");
                if (knowledgePoint != null && !activity.knowledgePoints.contains(knowledgePoint)) {
                    activity.knowledgePoints.add(knowledgePoint);
                }
            }
            return new CourseMapSnapshot(courses.values().stream().map(CourseBuilder::build).toList());
        } catch (SQLException | RuntimeException error) {
            throw new SqlTeacherException("COURSE_MAP_LOAD_FAILED", "Failed to load the local course map", error);
        }
    }

    private static String rowsUnchecked(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int intUnchecked(ResultSet rows, String column) {
        try {
            return rows.getInt(column);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class CourseBuilder {
        private final String id;
        private final String title;
        private final String version;
        private final Map<String, SectionBuilder> sections = new LinkedHashMap<>();

        private CourseBuilder(String id, String title, String version) {
            this.id = id;
            this.title = title;
            this.version = version;
        }

        private CourseMapCourse build() {
            return new CourseMapCourse(id, title, version, sections.values().stream().map(SectionBuilder::build).toList());
        }
    }

    private static final class SectionBuilder {
        private final String id;
        private final String title;
        private final int sortOrder;
        private final Map<String, ActivityBuilder> activities = new LinkedHashMap<>();

        private SectionBuilder(String id, String title, int sortOrder) {
            this.id = id;
            this.title = title;
            this.sortOrder = sortOrder;
        }

        private CourseMapSection build() {
            return new CourseMapSection(id, title, sortOrder, activities.values().stream().map(ActivityBuilder::build).toList());
        }
    }

    private static final class ActivityBuilder {
        private final String id;
        private final String title;
        private final ActivityType type;
        private final ActivityDifficulty difficulty;
        private final int estimatedMinutes;
        private final boolean enabled;
        private final List<String> knowledgePoints = new ArrayList<>();

        private ActivityBuilder(
            String id, String title, ActivityType type, ActivityDifficulty difficulty,
            int estimatedMinutes, boolean enabled
        ) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.difficulty = difficulty;
            this.estimatedMinutes = estimatedMinutes;
            this.enabled = enabled;
        }

        private CourseMapActivity build() {
            return new CourseMapActivity(id, title, type, difficulty, estimatedMinutes, enabled, knowledgePoints);
        }
    }
}
