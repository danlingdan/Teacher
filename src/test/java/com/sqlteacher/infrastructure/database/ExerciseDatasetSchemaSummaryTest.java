package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
