package com.sqlteacher.application.activity;

import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.SqlActivityArtifact;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityEvaluationDispatcherTest {
    @Test
    void shouldEvaluateLegacySqlThroughTheGenericActivityContract() {
        var legacy = (com.sqlteacher.application.exercise.SqlExerciseEvaluationService) (exercise, dataset, sql) ->
            new ExerciseEvaluationResult(
                true,
                List.of(new EvaluationCriterionResult("rows", true, "ok")),
                "passed",
                Duration.ofMillis(4),
                ""
            );
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(new SqlActivityEvaluator(legacy)));
        var adapter = new SqlLearningActivityAdapter();

        ActivityEvaluationResult result = dispatcher.evaluate(
            adapter.adapt(exercise(), dataset()),
            new SqlActivityArtifact("select id from sample")
        );

        assertEquals(ActivityEvaluationStatus.PASSED, result.status());
        assertEquals(SqlActivityEvaluator.VERSION, result.evaluatorVersion());
        assertEquals(SqlActivityEvaluator.EVIDENCE_VERSION, result.evidenceVersion());
        assertTrue(result.criteria().getFirst().passed());
    }

    @Test
    void shouldRejectAnActivityWhenNoEvaluatorIsRegistered() {
        LearningActivityDefinition definition = new SqlLearningActivityAdapter().adapt(exercise(), dataset());
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of());

        UnsupportedActivityException error = assertThrows(
            UnsupportedActivityException.class,
            () -> dispatcher.evaluate(definition, new SqlActivityArtifact("select id from sample"))
        );

        assertEquals("ACTIVITY_TYPE_UNSUPPORTED", error.errorCode());
    }

    @Test
    void shouldKeepTheLegacySqlEvaluationResultContract() {
        var legacy = (com.sqlteacher.application.exercise.SqlExerciseEvaluationService) (exercise, dataset, sql) ->
            new ExerciseEvaluationResult(
                false,
                List.of(new EvaluationCriterionResult("safety", false, "read only")),
                "rejected",
                Duration.ofMillis(2),
                "SQL_SAFETY_REJECTED"
            );
        var bridge = new ActivityBackedSqlExerciseEvaluationService(
            new DefaultActivityEvaluationDispatcher(List.of(new SqlActivityEvaluator(legacy)))
        );

        ExerciseEvaluationResult result = bridge.evaluate(exercise(), dataset(), "delete from sample");

        assertEquals("SQL_SAFETY_REJECTED", result.errorCode());
        assertEquals("safety", result.criteria().getFirst().criterion());
    }

    private static ExerciseDefinition exercise() {
        return new ExerciseDefinition(
            "sql-1", "SQL", "SQL activity", "Filtering", ExerciseDifficulty.BEGINNER,
            "dataset", "select id from sample", ExerciseEvaluationRule.exactResult(false),
            List.of(), 1, true, Instant.EPOCH, Instant.EPOCH
        );
    }

    private static ExerciseDataset dataset() {
        return new ExerciseDataset("dataset", "Dataset", "create table sample(id integer);", 1);
    }
}
