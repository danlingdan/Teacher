package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import com.sqlteacher.domain.exercise.ExerciseRevealMode;
import com.sqlteacher.domain.exercise.ExerciseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W2.1/W2.4/W3.3 kernel behavior: display score, comparison reveal, plan keywords. */
class ExerciseScoreAndComparisonTest {
    private static final String SETUP = """
        create table student(id integer primary key, name text, score integer);
        insert into student values (1, 'Alice', 90), (2, 'Bob', 70), (3, 'Carol', 55);
        """;

    @TempDir
    Path tempDir;

    private DeterministicSqlExerciseEvaluationService evaluator;

    @BeforeEach
    void setUp() {
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher", tempDir,
            new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db")),
            new AiConfiguration(URI.create("http://localhost:11434"), java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(2), "test")
        );
        evaluator = new DeterministicSqlExerciseEvaluationService(new DefaultSqlRiskAnalysisService(), configuration);
    }

    private ExerciseDataset dataset() {
        return new ExerciseDataset("test-data", "Test data", SETUP, 1);
    }

    private ExerciseDefinition queryExercise(ExerciseEvaluationRule rule) {
        return new ExerciseDefinition(
            "e1", "T", "D", "KP", ExerciseDifficulty.BEGINNER, "test-data",
            "select name from student where score >= 60 order by id",
            rule, List.of(), 1, true, Instant.EPOCH, Instant.EPOCH
        );
    }

    @Test
    void shouldWeightCriteriaIntoADisplayScoreWithoutChangingPassSemantics() {
        // columns 通过（25）而 rows 失败（75）：得分 25，仍不通过。
        ExerciseDefinition exercise = queryExercise(new ExerciseEvaluationRule(
            true, true, false, null, List.of(), Map.of("ROWS", 75, "COLUMNS", 25), List.of()));

        ExerciseEvaluationResult result = evaluator.evaluate(
            exercise, dataset(), "select name from student"
        );

        assertFalse(result.passed());
        assertNotNull(result.score());
        assertEquals(25, result.score());
        assertTrue(result.criteria().stream()
            .filter(item -> item.criterion().equals("columns"))
            .findFirst().orElseThrow().passed());
        assertFalse(result.criteria().stream()
            .filter(item -> item.criterion().equals("rows"))
            .findFirst().orElseThrow().passed());
    }

    @Test
    void shouldScoreFullCreditOnlyWhenEveryCriterionPasses() {
        ExerciseDefinition exercise = queryExercise(new ExerciseEvaluationRule(
            true, true, true, 2, List.of("ORDER"), Map.of("ORDER", 60), List.of()));

        ExerciseEvaluationResult correct = evaluator.evaluate(
            exercise, dataset(), "select name from student where score >= 60 order by id"
        );
        ExerciseEvaluationResult unordered = evaluator.evaluate(
            exercise, dataset(), "select name from student where score >= 60 order by id desc"
        );

        assertEquals(100, correct.score());
        assertTrue(correct.passed());
        assertFalse(unordered.passed());
        assertNotNull(unordered.score());
        assertTrue(unordered.score() < 100);
    }

    @Test
    void shouldAttachComparisonOnFailureButNotOnPassByDefault() {
        ExerciseDefinition exercise = queryExercise(ExerciseEvaluationRule.exactResult(false));

        ExerciseEvaluationResult failed = evaluator.evaluate(
            exercise, dataset(), "select name from student"
        );
        ExerciseEvaluationResult passed = evaluator.evaluate(
            exercise, dataset(), "select name from student where score >= 60 order by id"
        );

        assertNull(passed.comparison());
        assertNotNull(failed.comparison());
        assertTrue(failed.comparison().expectedRows().size() >= 2);
        failed.comparison().actualRows().forEach(row ->
            assertEquals(row.cells().size(), row.cellDiff().size()));
    }

    @Test
    void shouldNeverLeakExpectedRowsWhenRevealIsNever() {
        ExerciseDefinition exercise = new ExerciseDefinition(
            "e1", "T", "D", "KP", ExerciseDifficulty.BEGINNER, "test-data",
            "select name from student where score >= 60 order by id",
            ExerciseEvaluationRule.exactResult(false), List.of(), 1, true, Instant.EPOCH, Instant.EPOCH,
            ExerciseType.QUERY, null, List.of(), null, List.of(), null,
            List.of(), ExerciseRevealMode.NEVER
        );

        ExerciseEvaluationResult failed = evaluator.evaluate(
            exercise, dataset(), "select name from student"
        );

        assertFalse(failed.passed());
        assertNull(failed.comparison());
        assertFalse(String.valueOf(failed).contains("Alice"));
    }

    @Test
    void shouldGradePlanKeywordsOnExplainQueryPlanVerification() {
        // 数据集不建索引：参考答案与学生各自创建索引后，验证查询按执行计划断言。
        ExerciseDataset planDataset = new ExerciseDataset("test-data", "Test data", SETUP, 1);
        ExerciseDefinition exercise = new ExerciseDefinition(
            "idx-01", "Index", "Create the scoring index.", "Indexes", ExerciseDifficulty.ADVANCED,
            "test-data", "create index idx_score on student(score)",
            new ExerciseEvaluationRule(
                false, false, false, null, List.of(), Map.of(), List.of("INDEX IDX_SCORE")),
            List.of(), 1, true, Instant.EPOCH, Instant.EPOCH,
            ExerciseType.STATE, "explain query plan select id from student where score > 60",
            List.of("CREATE"), null, List.of(), null, List.of(), ExerciseRevealMode.ON_FAIL
        );

        ExerciseEvaluationResult wrongIndex = evaluator.evaluate(
            exercise, planDataset, "create index idx_name on student(name)"
        );
        ExerciseEvaluationResult rightIndex = evaluator.evaluate(
            exercise, planDataset, "create index idx_score on student(score)"
        );

        assertFalse(wrongIndex.passed());
        EvaluationCriterionResult plan = wrongIndex.criteria().stream()
            .filter(item -> item.criterion().equals("plan_keywords")).findFirst().orElseThrow();
        assertFalse(plan.passed());
        assertTrue(rightIndex.passed(), rightIndex.criteria().toString());
    }
}
