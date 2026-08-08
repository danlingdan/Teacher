package com.sqlteacher.application.activity;

import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.domain.activity.SqlActivityArtifact;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;

import java.util.Objects;

public final class ActivityBackedSqlExerciseEvaluationService implements SqlExerciseEvaluationService {
    private final ActivityEvaluationDispatcher dispatcher;
    private final SqlLearningActivityAdapter adapter;

    public ActivityBackedSqlExerciseEvaluationService(ActivityEvaluationDispatcher dispatcher) {
        this(dispatcher, new SqlLearningActivityAdapter());
    }

    ActivityBackedSqlExerciseEvaluationService(ActivityEvaluationDispatcher dispatcher, SqlLearningActivityAdapter adapter) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    @Override
    public ExerciseEvaluationResult evaluate(ExerciseDefinition exercise, ExerciseDataset dataset, String submittedSql) {
        ActivityEvaluationResult result = dispatcher.evaluate(
            adapter.adapt(exercise, dataset), new SqlActivityArtifact(submittedSql)
        );
        return new ExerciseEvaluationResult(
            result.passed(),
            result.criteria().stream().map(item -> new EvaluationCriterionResult(
                item.criterion(), item.passed(), item.feedback()
            )).toList(),
            result.summary(), result.resourceUsage().wallTime(), result.reasonCode()
        );
    }
}
