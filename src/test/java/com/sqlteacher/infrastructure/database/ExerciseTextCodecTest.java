package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import com.sqlteacher.domain.exercise.ExerciseType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseTextCodecTest {
    private final ExerciseTextCodec codec = new ExerciseTextCodec();

    @Test
    void shouldRoundTripEncodeAndDecode() {
        ExerciseDataset dataset = new ExerciseDataset(
            "school-core-v1",
            "学校核心数据集",
            "create table student(id integer primary key);\ninsert into student values\n    (1, 'Alice');",
            1
        );
        ExerciseDefinition exercise = new ExerciseDefinition(
            "query-01", "查询全部学生", "返回全部列。", "基础查询", ExerciseDifficulty.BEGINNER,
            "school-core-v1", "select id from student order by id",
            new ExerciseEvaluationRule(true, true, true, 3, List.of("SELECT", "FROM")),
            List.of("先写 SELECT。", "再写 FROM。"), 2, true,
            Instant.parse("2026-07-21T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z")
        );

        ExerciseTextCodec.DecodedPackage decoded =
            codec.decode(codec.encode(List.of(dataset), List.of(exercise)));

        assertEquals(List.of(dataset), decoded.datasets());
        assertEquals(List.of(exercise), decoded.exercises());
    }

    @Test
    void shouldApplyDefaultsForManualInputWithoutMetadata() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: beginner",
            "DATASET: d1", "DESCRIPTION:", "说明", "SQL:", "select x from t", "RULE: EXACT"
        );

        ExerciseTextCodec.DecodedPackage decoded = codec.decode(text);

        assertEquals(1, decoded.datasets().size());
        ExerciseDefinition exercise = decoded.exercises().get(0);
        assertFalse(exercise.id().isBlank());
        assertEquals(1, exercise.version());
        assertTrue(exercise.enabled());
        assertEquals(ExerciseDifficulty.BEGINNER, exercise.difficulty());
        assertNotNull(exercise.createdAt());
        assertNotNull(exercise.updatedAt());
        assertEquals(ExerciseEvaluationRule.exactResult(false), exercise.evaluationRule());
        assertTrue(exercise.hints().isEmpty());
    }

    @Test
    void shouldParseExactOrderRuleShorthand() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: BEGINNER",
            "DATASET: d1", "DESCRIPTION:", "说明", "SQL:", "select x from t", "RULE: EXACT ORDER"
        );

        ExerciseTextCodec.DecodedPackage decoded = codec.decode(text);

        assertEquals(ExerciseEvaluationRule.exactResult(true), decoded.exercises().get(0).evaluationRule());
    }

    @Test
    void shouldRejectMissingRequiredField() {
        String text = String.join("\n", "===[EXERCISE]===", "TITLE: 标题");

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> codec.decode(text));

        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
    }

    @Test
    void shouldRejectDuplicateIds() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: a", "SQL:", "create table t(x int);",
            "===[DATASET]===", "ID: d1", "NAME: b", "SQL:", "create table t(x int);"
        );

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> codec.decode(text));

        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
    }

    @Test
    void shouldRejectUnknownLabel() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "TITEL: x", "NAME: a", "SQL:", "create table t(x int);"
        );

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> codec.decode(text));

        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
    }

    @Test
    void shouldRejectUnsupportedVersion() {
        String text = String.join("\n",
            "# SQLTeacherExercisePackage 2",
            "===[DATASET]===", "ID: d1", "NAME: a", "SQL:", "create table t(x int);"
        );

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> codec.decode(text));

        assertEquals("EXERCISE_IMPORT_VERSION_UNSUPPORTED", error.errorCode());
    }

    @Test
    void shouldRejectBlankPackage() {
        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> codec.decode("   "));

        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
    }

    @Test
    void shouldRejectMoreThanThreeHints() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: BEGINNER",
            "DATASET: d1", "DESCRIPTION:", "说明", "SQL:", "select x from t", "RULE: EXACT",
            "HINTS:", "h1", "h2", "h3", "h4"
        );

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> codec.decode(text));

        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
    }

    @Test
    void shouldRoundTripNewTypeFields() {
        ExerciseDataset dataset = new ExerciseDataset(
            "school-core-v1", "学校核心数据集",
            "create table student(id integer primary key, name text, score integer);\n"
                + "create table audit(id integer);",
            1
        );
        ExerciseDefinition state = new ExerciseDefinition(
            "state-01", "提高分数", "把 1 号学生分数改为 95。", "数据更新", ExerciseDifficulty.BEGINNER,
            "school-core-v1", "update student set score = 95 where id = 1",
            ExerciseEvaluationRule.exactResult(false), List.of(), 1, true,
            Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"),
            ExerciseType.STATE, "select score from student where id = 1",
            List.of("INSERT", "UPDATE", "DELETE"), 1, List.of(), null
        );
        ExerciseDefinition script = new ExerciseDefinition(
            "script-01", "事务脚本", "在事务中插入一名学生。", "事务", ExerciseDifficulty.INTERMEDIATE,
            "school-core-v1", "begin;\ninsert into student values (9, 'Zoe', 60);\ncommit;",
            ExerciseEvaluationRule.exactResult(false), List.of(), 1, true,
            Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"),
            ExerciseType.SCRIPT, "select count(*) from student", List.of(), null,
            List.of("BEGIN", "COMMIT"), null
        );
        ExerciseDefinition trigger = new ExerciseDefinition(
            "trigger-01", "审计触发器", "插入学生时写入审计表。", "触发器", ExerciseDifficulty.ADVANCED,
            "school-core-v1", "create trigger trg after insert on student begin "
                + "insert into audit values (new.id); end",
            ExerciseEvaluationRule.exactResult(false), List.of(), 1, true,
            Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"),
            ExerciseType.TRIGGER, "select id from audit", List.of(), null, List.of(),
            "insert into student values (99, 'New', 10);"
        );

        ExerciseTextCodec.DecodedPackage decoded =
            codec.decode(codec.encode(List.of(dataset), List.of(state, script, trigger)));

        assertEquals(List.of(dataset), decoded.datasets());
        assertEquals(List.of(state, script, trigger), decoded.exercises());
    }

    @Test
    void shouldDefaultLegacyPackagesToQueryType() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: BEGINNER",
            "DATASET: d1", "DESCRIPTION:", "说明", "SQL:", "select x from t", "RULE: EXACT"
        );

        ExerciseDefinition exercise = codec.decode(text).exercises().get(0);

        assertEquals(ExerciseType.QUERY, exercise.exerciseType());
        assertNull(exercise.verificationSql());
        assertTrue(exercise.allowedStatementTypes().isEmpty());
        assertNull(exercise.expectedAffectedRows());
        assertTrue(exercise.requiredTransactionKeywords().isEmpty());
        assertNull(exercise.triggerProbeSql());
    }

    @Test
    void shouldParseNewLabelsFromText() {
        String text = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: BEGINNER",
            "DATASET: d1", "TYPE: STATE", "DESCRIPTION:", "说明",
            "SQL:", "update t set x = 1", "VERIFY:", "select x from t",
            "ALLOWED: UPDATE, DELETE", "AFFECTED: 1", "RULE: EXACT"
        );

        ExerciseDefinition exercise = codec.decode(text).exercises().get(0);

        assertEquals(ExerciseType.STATE, exercise.exerciseType());
        assertEquals("select x from t", exercise.verificationSql());
        assertEquals(List.of("UPDATE", "DELETE"), exercise.allowedStatementTypes());
        assertEquals(1, exercise.expectedAffectedRows());
    }

    @Test
    void shouldRejectUnknownTypeOrStatementClass() {
        String badType = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: BEGINNER",
            "DATASET: d1", "TYPE: MAGIC", "DESCRIPTION:", "说明", "SQL:", "select x from t"
        );
        String badAllowed = String.join("\n",
            "===[DATASET]===", "ID: d1", "NAME: 数据集", "SQL:", "create table t(x int);",
            "===[EXERCISE]===", "TITLE: 标题", "KNOWLEDGE: 知识点", "DIFFICULTY: BEGINNER",
            "DATASET: d1", "TYPE: STATE", "DESCRIPTION:", "说明", "SQL:", "update t set x = 1",
            "VERIFY:", "select x from t", "ALLOWED: GRANT"
        );

        assertThrows(SqlTeacherException.class, () -> codec.decode(badType));
        assertThrows(SqlTeacherException.class, () -> codec.decode(badAllowed));
    }
}
