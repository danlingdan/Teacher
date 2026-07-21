package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDataset;

public interface SqlExerciseEvaluationService {
    ExerciseEvaluationResult evaluate(
        ExerciseDefinition exercise,
        ExerciseDataset dataset,
        String submittedSql
    );
}
