package com.sqlteacher.application.exercise;

import java.util.List;
import java.util.Optional;

public interface ExerciseCatalogService {
    List<ExerciseSummary> listAvailableExercises();

    Optional<ExerciseView> findAvailableExercise(String exerciseId);
}
