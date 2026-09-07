package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseDatasetSchemaSummaryTest {
    @Test
    void shouldExtractStudentFacingTableAndColumnSummary() {
        String setup = """
            create table student(id integer primary key, name text not null, score integer not null);
            create table enrollment(student_id integer, course_id integer,
                primary key(student_id, course_id));
            """;

        assertEquals(
            "student（id、name、score）；enrollment（student_id、course_id）",
            ExerciseDatasetSchemaSummary.fromSetupSql(setup)
        );
    }

    @Test
    void shouldAppendDeclaredForeignKeysAsReadableRelations() {
        String setup = """
            create table student(id integer primary key, name text not null);
            create table course(id integer primary key, name text not null);
            create table enrollment(student_id integer not null, course_id integer not null, grade integer not null,
                primary key(student_id, course_id),
                foreign key(student_id) references student(id),
                foreign key(course_id) references course(id));
            """;

        String summary = ExerciseDatasetSchemaSummary.fromSetupSql(setup);

        assertTrue(summary.startsWith("student（id、name）；course（id、name）；enrollment（student_id、course_id、grade）"), summary);
        // 关系行独立成行，声明顺序展示：student_id 在 course_id 之前。
        String relationLines = summary.substring(summary.indexOf('\n') + 1);
        assertTrue(relationLines.startsWith("enrollment.student_id → student.id；enrollment.course_id → course.id"), summary);
    }

    @Test
    void shouldReturnPlaceholderWhenSetupSqlIsInvalid() {
        assertEquals("暂无数据集字段说明", ExerciseDatasetSchemaSummary.fromSetupSql("create table broken("));
        assertEquals("暂无数据集字段说明", ExerciseDatasetSchemaSummary.fromSetupSql("pragma evil;"));
        assertEquals("暂无数据集字段说明", ExerciseDatasetSchemaSummary.fromSetupSql("  "));
        assertEquals("暂无数据集字段说明", ExerciseDatasetSchemaSummary.fromSetupSql(null));
    }
}
