package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
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
