package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDataset;

import java.util.List;
import java.util.Optional;

public interface ExerciseManagementService {
    List<ExerciseSummary> listExercises(boolean includeDisabled);

    Optional<ExerciseDefinition> findDefinition(String exerciseId);

    List<ExerciseDataset> listDatasets();

    ExerciseDefinition save(ExerciseDraft draft);

    ExerciseDefinition copy(String exerciseId, String newTitle);

    ExerciseDefinition setEnabled(String exerciseId, boolean enabled, int expectedVersion);

    String exportPackage(List<String> exerciseIds);

    ExerciseImportResult importPackage(String packageJson);
}
