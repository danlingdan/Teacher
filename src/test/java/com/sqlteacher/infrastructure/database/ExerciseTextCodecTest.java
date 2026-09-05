package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
