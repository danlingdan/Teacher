package com.sqlteacher.application.exercise;

import java.util.List;
import java.util.Optional;

public interface ExerciseCatalogService {
    List<ExerciseCatalogItem> listAvailableExercises();

    Optional<ExerciseView> findAvailableExercise(String exerciseId);
}
