package com.sqlteacher.application.exercise;

import java.util.List;

public interface ExerciseProgressService {
    ExerciseProgressOverview overview();

    List<ExerciseProgressItem> listExerciseProgress();
}
