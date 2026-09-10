package com.sqlteacher.application.exercise;

import java.util.List;
import java.util.Optional;

public interface ExerciseCatalogService {
    List<ExerciseCatalogItem> listAvailableExercises();

    Optional<ExerciseView> findAvailableExercise(String exerciseId);

    /** Attempted-but-never-passed exercises for the current owner, newest attempt first. */
    List<WrongBookItem> wrongBook();

    /** Deterministic next-practice suggestion from the local attempt history. */
    Optional<RecommendationView> recommendNextExercise();

    /** One bounded, filtered catalog page (v3.3 W4.4); filters are optional. */
    ExerciseCatalogPage listExercises(
        int page, int pageSize, String query, String difficulty, String status);
}
