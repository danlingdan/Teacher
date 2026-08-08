package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.activity.ActivityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcCourseMapServiceTest {
    @TempDir Path tempDir;

    @Test
    void shouldLoadTheBackfilledSqlCourseMap() {
        DatabaseConfiguration databases = new DatabaseConfiguration(
            tempDir.resolve("app.db"), tempDir.resolve("demo.db")
        );
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test"
            )
        )).initialize();

        var snapshot = new JdbcCourseMapService(new JdbcConnectionFactory(databases)).load();

        assertEquals(3, snapshot.courses().size());
        assertEquals(26, snapshot.activityCount());
        var sqlCourse = snapshot.courses().stream().filter(course -> course.id().equals("builtin-data-management"))
            .findFirst().orElseThrow();
        assertEquals(1, sqlCourse.sections().size());
        assertFalse(sqlCourse.sections().getFirst().activities().getFirst().knowledgePoints().isEmpty());
        assertEquals(
            ActivityType.SQL,
            sqlCourse.sections().getFirst().activities().getFirst().type()
        );
        var treeCourse = snapshot.courses().stream().filter(course -> course.id().equals("builtin-data-structures"))
            .findFirst().orElseThrow();
        assertEquals(
            java.util.Set.of(ActivityType.QUIZ, ActivityType.TRACE),
            treeCourse.sections().getFirst().activities().stream().map(item -> item.type())
                .collect(java.util.stream.Collectors.toSet())
        );
        var programming = snapshot.courses().stream()
            .filter(course -> course.id().equals("builtin-programming-basics")).findFirst().orElseThrow();
        assertEquals(4, programming.sections().getFirst().activities().size());
        assertEquals(java.util.Set.of(ActivityType.CODE),
            programming.sections().getFirst().activities().stream().map(item -> item.type())
                .collect(java.util.stream.Collectors.toSet()));
    }
}
