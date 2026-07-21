package com.sqlteacher.application.exercise;

import java.util.List;
import java.util.Objects;

public record ExerciseImportResult(
    int datasetsImported,
    int exercisesImported,
    List<String> importedExerciseIds
) {
    public ExerciseImportResult {
        if (datasetsImported < 0 || exercisesImported < 0) {
            throw new IllegalArgumentException("import counts must not be negative");
        }
        importedExerciseIds = List.copyOf(
            Objects.requireNonNull(importedExerciseIds, "importedExerciseIds must not be null")
        );
        if (exercisesImported != importedExerciseIds.size()) {
            throw new IllegalArgumentException("exercise count must match imported IDs");
        }
    }
}
