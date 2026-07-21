package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.application.exercise.ExerciseView;

import java.util.List;
import java.util.Optional;

public final class JdbcExerciseCatalogService implements ExerciseCatalogService {
    private final ExerciseManagementService managementService;

    public JdbcExerciseCatalogService(ExerciseManagementService managementService) {
        this.managementService = managementService;
    }

    @Override
    public List<ExerciseSummary> listAvailableExercises() {
        return managementService.listExercises(false);
    }

    @Override
    public Optional<ExerciseView> findAvailableExercise(String exerciseId) {
        return managementService.findDefinition(exerciseId)
            .filter(definition -> definition.enabled())
            .map(definition -> new ExerciseView(
                definition.id(), definition.title(), definition.description(), definition.knowledgePoint(),
                definition.difficulty(), definition.version()
            ));
    }
}
