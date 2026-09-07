package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExercisePackageValidatorTest {
    private final ExercisePackageValidator validator = new ExercisePackageValidator(new DefaultSqlRiskAnalysisService());

    private static final String SETUP_SQL = """
        create table student(id integer primary key, name text not null, score integer not null);
        insert into student values (1, 'Alice', 92), (2, 'Bob', 76), (3, 'Carol', 55);
        """;

    @Test
    void shouldPassWellFormedPackage() {
        ExerciseDataset dataset = dataset(SETUP_SQL);
        ExerciseDefinition exercise = exercise(
            "select name from student where score >= 60 order by id",
            ExerciseEvaluationRule.exactResult(true)
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(dataset), List.of(exercise), id -> Optional.empty()
        );

        assertTrue(result.passed(), result.failures().toString());
        assertTrue(result.datasets().getFirst().passed());
        assertTrue(result.exercises().getFirst().passed());
    }

    @Test
    void shouldFailExerciseWhoseReferenceSqlDoesNotExecute() {
        ExerciseDataset dataset = dataset(SETUP_SQL);
        ExerciseDefinition exercise = exercise(
            "select name from missing_table",
            ExerciseEvaluationRule.exactResult(false)
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(dataset), List.of(exercise), id -> Optional.empty()
        );

        assertFalse(result.passed());
        assertEquals("参考答案未能在数据集上执行", result.exercises().getFirst().message());
    }

    @Test
    void shouldFailExerciseWhoseReferenceSqlIsNotSingleSelect() {
        ExerciseDataset dataset = dataset(SETUP_SQL);
        ExerciseDefinition exercise = exercise(
            "delete from student",
            ExerciseEvaluationRule.exactResult(false)
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(dataset), List.of(exercise), id -> Optional.empty()
        );

        assertFalse(result.passed());
        assertEquals("参考答案必须是单条只读 SELECT 查询", result.exercises().getFirst().message());
    }

    @Test
    void shouldFailExerciseViolatingItsOwnRowCountRule() {
        ExerciseDataset dataset = dataset(SETUP_SQL);
        ExerciseDefinition exercise = exercise(
            "select name from student",
            new ExerciseEvaluationRule(true, true, false, 7, List.of())
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(dataset), List.of(exercise), id -> Optional.empty()
        );

        assertFalse(result.passed());
        String message = result.exercises().getFirst().message();
        assertTrue(message.contains("3 行") && message.contains("7 行"), message);
    }

    @Test
    void shouldFailExerciseViolatingItsOwnKeywordRule() {
        ExerciseDataset dataset = dataset(SETUP_SQL);
        ExerciseDefinition exercise = exercise(
            "select name from student where score >= 60",
            new ExerciseEvaluationRule(true, true, false, null, List.of("HAVING"))
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(dataset), List.of(exercise), id -> Optional.empty()
        );

        assertFalse(result.passed());
        assertEquals("参考答案未使用题目要求的结构关键字：HAVING", result.exercises().getFirst().message());
    }

    @Test
    void shouldFailExerciseReferencingMissingDataset() {
        ExerciseDefinition exercise = exercise(
            "select name from student",
            ExerciseEvaluationRule.exactResult(false)
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(), List.of(exercise), id -> Optional.empty()
        );

        assertFalse(result.passed());
        assertEquals("引用的数据集不存在：test-data", result.exercises().getFirst().message());
    }

    @Test
    void shouldResolveStoredDatasetForSelfTest() {
        ExerciseDataset stored = dataset(SETUP_SQL);
        ExerciseDefinition exercise = exercise(
            "select name from student",
            new ExerciseEvaluationRule(true, true, false, 3, List.of())
        );

        ExercisePackageValidator.Result result = validator.validate(
            List.of(), List.of(exercise), id -> "test-data".equals(id) ? Optional.of(stored) : Optional.empty()
        );

        assertTrue(result.passed(), result.failures().toString());
    }

    @Test
    void shouldFailDatasetWithPolicyViolationOrBrokenSql() {
        ExerciseDataset policyViolation = dataset("create table student(id integer); pragma evil;");
        ExerciseDataset brokenSql = dataset("create table student(id integer); insert into student values;");

        ExercisePackageValidator.Result result = validator.validate(
            List.of(policyViolation, brokenSql), List.of(), id -> Optional.empty()
        );

        assertFalse(result.passed());
        assertTrue(result.datasets().stream().allMatch(status -> !status.passed()));
    }

    private static ExerciseDataset dataset(String setupSql) {
        return new ExerciseDataset("test-data", "Test data", setupSql, 1);
    }

    private static ExerciseDefinition exercise(String referenceSql, ExerciseEvaluationRule rule) {
        return new ExerciseDefinition(
            "test-exercise",
            "Test",
            "Return students scoring at least 60.",
            "Filtering",
            ExerciseDifficulty.BEGINNER,
            "test-data",
            referenceSql,
            rule,
            List.of(),
            1,
            true,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }
}
