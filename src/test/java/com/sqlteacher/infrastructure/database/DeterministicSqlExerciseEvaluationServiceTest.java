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
import com.sqlteacher.domain.exercise.ExerciseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicSqlExerciseEvaluationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptDifferentSqlWithEquivalentResult() {
        Fixture fixture = fixture();
        ExerciseDefinition exercise = exercise(ExerciseEvaluationRule.exactResult(true));

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            exercise,
            fixture.dataset(),
            "select name from student where not score < 80 order by name"
        );

        assertTrue(result.passed());
        assertTrue(result.criteria().stream().allMatch(criterion -> criterion.passed()));
    }

    @Test
    void shouldReportOrderingWithoutLeakingExpectedRows() {
        Fixture fixture = fixture();

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            exercise(ExerciseEvaluationRule.exactResult(true)),
            fixture.dataset(),
            "select name from student where score >= 80 order by name desc"
        );

        assertFalse(result.passed());
        assertTrue(result.criteria().stream().filter(item -> item.criterion().equals("rows")).findFirst().orElseThrow().passed());
        assertFalse(result.criteria().stream().filter(item -> item.criterion().equals("order")).findFirst().orElseThrow().passed());
        assertFalse(result.feedback().contains("Alice"));
    }

    @Test
    void shouldEnforceRequiredStructureAndRejectMutation() {
        Fixture fixture = fixture();
        ExerciseDefinition structural = exercise(
            new ExerciseEvaluationRule(true, true, false, null, List.of("WHERE"))
        );

        ExerciseEvaluationResult missingStructure = fixture.evaluator().evaluate(
            structural,
            fixture.dataset(),
            "select name from student order by name limit 2"
        );
        ExerciseEvaluationResult mutation = fixture.evaluator().evaluate(
            structural,
            fixture.dataset(),
            "delete from student"
        );

        assertFalse(missingStructure.passed());
        assertEquals("structure", missingStructure.criteria().getLast().criterion());
        assertFalse(mutation.passed());
        assertEquals("SQL_SAFETY_REJECTED", mutation.errorCode());
    }

    @Test
    void shouldAttributeReferenceFailureToContentNotStudent() {
        Fixture fixture = fixture();
        ExerciseDefinition broken = new ExerciseDefinition(
            "test-exercise",
            "Test",
            "Return students scoring at least 80.",
            "Filtering",
            ExerciseDifficulty.BEGINNER,
            "test-data",
            "select name from missing_table",
            ExerciseEvaluationRule.exactResult(true),
            List.of("Use WHERE."),
            1,
            true,
            Instant.EPOCH,
            Instant.EPOCH
        );

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            broken, fixture.dataset(), "select name from student order by name"
        );

        assertFalse(result.passed());
        assertEquals("REFERENCE_SQL_FAILED", result.errorCode());
        assertTrue(result.criteria().getFirst().feedback().contains("题目数据"));
    }

    @Test
    void shouldTreatPolicyViolatingDatasetAsContentFailure() {
        SqlTeacherConfiguration configuration = configuration(tempDir.resolve("policy"));
        DeterministicSqlExerciseEvaluationService evaluator = new DeterministicSqlExerciseEvaluationService(
            new DefaultSqlRiskAnalysisService(), configuration
        );
        ExerciseDataset violating = new ExerciseDataset(
            "bad-data",
            "Bad data",
            "create table student(id integer); pragma evil;",
            1
        );

        ExerciseEvaluationResult result = evaluator.evaluate(
            exercise(ExerciseEvaluationRule.exactResult(false)), violating, "select 1"
        );

        assertFalse(result.passed());
        assertEquals("REFERENCE_SQL_FAILED", result.errorCode());
    }

    @Test
    void shouldRevealExpectedColumnNamesOnColumnMismatch() {
        Fixture fixture = fixture();

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            exercise(ExerciseEvaluationRule.exactResult(false)),
            fixture.dataset(),
            "select id, name from student where score >= 80 order by name"
        );

        EvaluationCriterionResult columns = result.criteria().stream()
            .filter(item -> item.criterion().equals("columns"))
            .findFirst()
            .orElseThrow();
        assertFalse(result.passed());
        assertFalse(columns.passed());
        assertTrue(columns.feedback().contains("期望列：name"), columns.feedback());
        assertTrue(columns.feedback().contains("实际列：id、name"), columns.feedback());
    }

    @Test
    void shouldEvaluateEveryBuiltInReferenceQuery() {
        SqlTeacherConfiguration configuration = configuration(tempDir.resolve("builtins"));
        new SqliteAppDatabaseInitializer(configuration).initialize();
        JdbcExerciseManagementService management = new JdbcExerciseManagementService(
            new JdbcConnectionFactory(configuration.database())
        );
        DeterministicSqlExerciseEvaluationService evaluator = new DeterministicSqlExerciseEvaluationService(
            new DefaultSqlRiskAnalysisService(), configuration
        );
        ExerciseDataset dataset = management.listDatasets().getFirst();

        List<ExerciseDefinition> exercises = management.listExercises(false).stream()
            .map(summary -> management.findDefinition(summary.id()).orElseThrow())
            .toList();

        assertEquals(20, exercises.size());
        assertTrue(exercises.stream().allMatch(exercise ->
            evaluator.evaluate(exercise, dataset, exercise.referenceSql()).passed()
        ));
    }

    @Test
    void shouldGradeStateSubmissionThroughVerificationQuery() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.STATE,
            "update student set score = 95 where id = 1",
            "select name, score from student where score >= 95",
            ExerciseEvaluationRule.exactResult(true), 1, List.of(), List.of(), null
        );

        ExerciseEvaluationResult correct = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "update student set score = 95 where id = 1"
        );
        ExerciseEvaluationResult wrongValue = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "update student set score = 90 where id = 1"
        );
        ExerciseEvaluationResult wrongRows = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "update student set score = 95"
        );

        assertTrue(correct.passed(), correct.criteria().toString());
        assertFalse(wrongValue.passed());
        assertFalse(wrongRows.passed());
        assertFalse(wrongRows.criteria().stream().filter(item -> item.criterion().equals("rows"))
            .findFirst().orElseThrow().passed());
    }

    @Test
    void shouldGradeStateAffectedRowsCriterion() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.STATE,
            "update student set score = 95 where id = 1",
            "select score from student where id = 1",
            new ExerciseEvaluationRule(true, true, false, null, List.of()), 2, List.of(), List.of(), null
        );

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "update student set score = 95 where id = 1"
        );

        assertFalse(result.passed());
        EvaluationCriterionResult affected = result.criteria().stream()
            .filter(item -> item.criterion().equals("affected_rows")).findFirst().orElseThrow();
        assertFalse(affected.passed());
        assertTrue(affected.feedback().contains("实际影响 1 行"), affected.feedback());
    }

    @Test
    void shouldRejectStateTypeViolationAndForbiddenStatements() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.STATE,
            "update student set score = 95 where id = 1",
            "select score from student where id = 1",
            ExerciseEvaluationRule.exactResult(false), null, List.of(), List.of(), null
        );

        ExerciseEvaluationResult select = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "select * from student"
        );
        ExerciseEvaluationResult multi = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "update student set score = 1 where id = 1; delete from student"
        );
        ExerciseEvaluationResult attach = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "attach database 'x.db' as extra"
        );

        assertEquals("SQL_SAFETY_REJECTED", select.errorCode());
        assertTrue(select.criteria().getFirst().feedback().contains("INSERT、UPDATE、DELETE"));
        assertEquals("SQL_SAFETY_REJECTED", multi.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", attach.errorCode());
    }

    @Test
    void shouldAllowCreateWhenTeacherDeclaresItForState() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.STATE,
            "create table bonus(id integer)",
            "select count(*) from sqlite_master where name = 'bonus'",
            new ExerciseEvaluationRule(true, true, false, 1, List.of()), null,
            List.of("CREATE"), List.of(), null
        );

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "create table bonus(id integer)"
        );

        assertTrue(result.passed(), result.criteria().toString());
    }

    @Test
    void shouldGradeScriptWithTransactionKeywordCriterion() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.SCRIPT,
            "begin;\ninsert into student values (9, 'Zoe', 60);\ncommit;",
            "select count(*) from student",
            new ExerciseEvaluationRule(true, true, false, 1, List.of()), null,
            List.of(), List.of("BEGIN", "COMMIT"), null
        );

        ExerciseEvaluationResult correct = fixture.evaluator().evaluate(
            exercise, fixture.dataset(),
            "begin;\ninsert into student values (9, 'Zoe', 60);\ncommit;"
        );
        ExerciseEvaluationResult withoutTransaction = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "insert into student values (9, 'Zoe', 60);"
        );

        assertTrue(correct.passed(), correct.criteria().toString());
        assertFalse(withoutTransaction.passed());
        EvaluationCriterionResult transaction = withoutTransaction.criteria().stream()
            .filter(item -> item.criterion().equals("transaction")).findFirst().orElseThrow();
        assertFalse(transaction.passed());
        assertTrue(withoutTransaction.criteria().stream()
            .filter(item -> item.criterion().equals("rows")).findFirst().orElseThrow().passed());
    }

    @Test
    void shouldRejectForbiddenStatementInsideScript() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.SCRIPT,
            "begin;\ninsert into student values (9, 'Zoe', 60);\ncommit;",
            "select count(*) from student",
            ExerciseEvaluationRule.exactResult(false), null, List.of(), List.of(), null
        );

        ExerciseEvaluationResult attach = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "begin;\nattach database 'x.db' as extra;\ncommit;"
        );
        ExerciseEvaluationResult pragma = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "pragma evil = 1;"
        );

        assertEquals("SQL_SAFETY_REJECTED", attach.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", pragma.errorCode());
        assertTrue(attach.criteria().getFirst().feedback().contains("脚本第 2 条"), attach.feedback());
    }

    @Test
    void shouldGradeTriggerThroughProbeState() {
        Fixture fixture = triggerFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.TRIGGER,
            "create trigger trg after insert on student begin "
                + "insert into audit values (new.id); end",
            "select id from audit",
            ExerciseEvaluationRule.exactResult(true), null, List.of(), List.of(),
            "insert into student values (99, 'New', 10);"
        );

        ExerciseEvaluationResult correct = fixture.evaluator().evaluate(
            exercise, fixture.dataset(),
            "create trigger trg after insert on student begin "
                + "insert into audit values (new.id); end"
        );
        ExerciseEvaluationResult wrongBody = fixture.evaluator().evaluate(
            exercise, fixture.dataset(),
            "create trigger trg after insert on student begin "
                + "insert into audit values (0); end"
        );
        ExerciseEvaluationResult notATrigger = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "insert into audit values (99)"
        );

        assertTrue(correct.passed(), correct.criteria().toString());
        assertFalse(wrongBody.passed());
        assertEquals("SQL_SAFETY_REJECTED", notATrigger.errorCode());
    }

    @Test
    void shouldAttributeBrokenStateReferenceToContent() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition broken = stateful(
            ExerciseType.STATE,
            "update missing_table set x = 1",
            "select 1",
            ExerciseEvaluationRule.exactResult(false), null, List.of(), List.of(), null
        );

        ExerciseEvaluationResult result = fixture.evaluator().evaluate(
            broken, fixture.dataset(), "update student set score = 1 where id = 1"
        );

        assertFalse(result.passed());
        assertEquals("REFERENCE_SQL_FAILED", result.errorCode());
    }

    @Test
    void shouldPassEveryNewTypeWhenTheReferenceAnswerIsResubmitted() {
        Fixture stateful = statefulFixture();
        ExerciseDefinition state = stateful(
            ExerciseType.STATE,
            "delete from student where id = 3",
            "select id from student",
            ExerciseEvaluationRule.exactResult(true), 1,
            List.of(), List.of(), null
        );
        assertTrue(stateful.evaluator().evaluate(state, stateful.dataset(), state.referenceSql()).passed());

        Fixture trigger = triggerFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.TRIGGER,
            "create trigger trg after insert on student begin "
                + "insert into audit values (new.id); end",
            "select id from audit",
            ExerciseEvaluationRule.exactResult(true), null, List.of(), List.of(),
            "insert into student values (99, 'New', 10);"
        );
        assertTrue(trigger.evaluator().evaluate(exercise, trigger.dataset(), exercise.referenceSql()).passed());
    }

    @Test
    void shouldAttributeStateSyntaxErrorAndBrokenTableToTheStudent() {
        Fixture fixture = statefulFixture();
        ExerciseDefinition exercise = stateful(
            ExerciseType.STATE,
            "update student set score = 95 where id = 1",
            "select name from student where score >= 95",
            ExerciseEvaluationRule.exactResult(false), null, List.of(), List.of(), null
        );
        ExerciseDefinition dropper = stateful(
            ExerciseType.STATE,
            "update student set score = 95 where id = 1",
            "select name from student where score >= 95",
            ExerciseEvaluationRule.exactResult(false), null, List.of("DROP"), List.of(), null
        );

        ExerciseEvaluationResult syntaxError = fixture.evaluator().evaluate(
            exercise, fixture.dataset(), "update student set score = 95 where id ="
        );
        ExerciseEvaluationResult brokenTable = fixture.evaluator().evaluate(
            dropper, fixture.dataset(), "drop table student"
        );

        assertFalse(syntaxError.passed());
        assertEquals("SQL_EXECUTION_FAILED", syntaxError.errorCode());
        assertTrue(syntaxError.criteria().getFirst().feedback().contains("第 1 条语句"), syntaxError.feedback());
        assertFalse(brokenTable.passed());
        assertEquals("RESULT_MISMATCH", brokenTable.errorCode());
        assertTrue(brokenTable.criteria().getFirst().feedback().contains("验证查询"), brokenTable.feedback());
    }

    private Fixture fixture() {
        SqlTeacherConfiguration configuration = configuration(tempDir);
        ExerciseDataset dataset = new ExerciseDataset(
            "test-data",
            "Test data",
            """
                create table student(id integer primary key, name text, score integer);
                insert into student values (1, 'Alice', 90), (2, 'Bob', 70), (3, null, 85);
                """,
            1
        );
        return new Fixture(
            new DeterministicSqlExerciseEvaluationService(new DefaultSqlRiskAnalysisService(), configuration),
            dataset
        );
    }

    private static ExerciseDefinition exercise(ExerciseEvaluationRule rule) {
        return new ExerciseDefinition(
            "test-exercise",
            "Test",
            "Return students scoring at least 80.",
            "Filtering",
            ExerciseDifficulty.BEGINNER,
            "test-data",
            "select name from student where score >= 80 order by name",
            rule,
            List.of("Use WHERE."),
            1,
            true,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private static ExerciseDefinition stateful(
        ExerciseType type,
        String referenceSql,
        String verificationSql,
        ExerciseEvaluationRule rule,
        Integer affectedRows,
        List<String> allowedTypes,
        List<String> transactionKeywords,
        String probeSql
    ) {
        return new ExerciseDefinition(
            "stateful-exercise", "Test", "Stateful exercise.", "Filtering",
            ExerciseDifficulty.BEGINNER, "test-data", referenceSql, rule, List.of(), 1, true,
            Instant.EPOCH, Instant.EPOCH,
            type, verificationSql, allowedTypes, affectedRows, transactionKeywords, probeSql
        );
    }

    private Fixture statefulFixture() {
        SqlTeacherConfiguration configuration = configuration(tempDir);
        ExerciseDataset dataset = new ExerciseDataset(
            "test-data",
            "Test data",
            """
                create table student(id integer primary key, name text, score integer);
                insert into student values (1, 'Alice', 90), (2, 'Bob', 70), (3, null, 85);
                """,
            1
        );
        return new Fixture(
            new DeterministicSqlExerciseEvaluationService(new DefaultSqlRiskAnalysisService(), configuration),
            dataset
        );
    }

    private Fixture triggerFixture() {
        SqlTeacherConfiguration configuration = configuration(tempDir.resolve("trigger"));
        ExerciseDataset dataset = new ExerciseDataset(
            "test-data",
            "Test data",
            """
                create table student(id integer primary key, name text, score integer);
                insert into student values (1, 'Alice', 90), (2, 'Bob', 70), (3, null, 85);
                create table audit(id integer);
                """,
            1
        );
        return new Fixture(
            new DeterministicSqlExerciseEvaluationService(new DefaultSqlRiskAnalysisService(), configuration),
            dataset
        );
    }

    private static SqlTeacherConfiguration configuration(Path directory) {
        return new SqlTeacherConfiguration(
            "SQLTeacher",
            directory,
            new DatabaseConfiguration(directory.resolve("app.db"), directory.resolve("demo.db")),
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test"
            )
        );
    }

    private record Fixture(
        DeterministicSqlExerciseEvaluationService evaluator,
        ExerciseDataset dataset
    ) {
    }
}
