package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseDatasetSqlPolicyTest {
    @Test
    void shouldAcceptWhitelistedBuildStatements() {
        ExerciseDatasetSqlPolicy.validate("""
            create table student(id integer primary key, name text not null);
            CREATE UNIQUE INDEX idx_student_name ON student(name);
            create index if not exists idx_student_class on student(name);
            insert into student values (1, 'Alice'), (2, 'Bob');
            INSERT INTO student VALUES (3, '卡罗尔; -- 注入尝试');
            -- trailing comment
            """);
    }

    @Test
    void shouldRejectEmptyScript() {
        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> ExerciseDatasetSqlPolicy.validate("  \n "));
        assertEquals("EXERCISE_DATASET_POLICY_VIOLATION", error.errorCode());
    }

    @Test
    void shouldRejectNonWhitelistedStatementTypes() {
        String[] scripts = {
            "pragma foreign_keys = on;",
            "attach database 'other.db' as other;",
            "create table student(id integer); delete from student;",
            "update student set name = 'x';",
            "drop table student;",
            "alter table student add column age integer;",
            "create view v as select 1;",
            "create trigger t after insert on student begin select 1; end;",
            "begin; insert into student values (1); commit;",
            "select 1;"
        };
        for (String script : scripts) {
            SqlTeacherException error = assertThrows(
                SqlTeacherException.class, () -> ExerciseDatasetSqlPolicy.validate(script),
                () -> "script should be rejected: " + script
            );
            assertEquals("EXERCISE_DATASET_POLICY_VIOLATION", error.errorCode());
            assertTrue(error.getMessage().contains("语句类型不被允许"), error.getMessage());
        }
    }

    @Test
    void shouldReportStatementIndexWithoutStatementContent() {
        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> ExerciseDatasetSqlPolicy.validate("""
            create table student(id integer);
            insert into student values (1, 'secret-value');
            delete from student;
            """));

        assertTrue(error.getMessage().contains("第 3 条"), error.getMessage());
        assertTrue(error.getMessage().contains("仅支持"), error.getMessage());
    }

    @Test
    void shouldSkipCommentOnlyStatements() {
        ExerciseDatasetSqlPolicy.validate("""
            -- leading comment without executable text
            /* block comment */
            """);
    }

    @Test
    void shouldRejectQuotedKeywordSmuggling() {
        // Keywords hidden behind quoted identifiers or comments must not bypass the whitelist.
        String[] scripts = {
            "\"delete\";-- placeholder\nDELETE FROM student;",
            "create table student(id integer);\n/*)*/ PRAGMA evil;"
        };
        for (String script : scripts) {
            assertThrows(SqlTeacherException.class, () -> ExerciseDatasetSqlPolicy.validate(script),
                () -> "script should be rejected: " + script);
        }
    }
}
