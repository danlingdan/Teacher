package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.SqlActivitySpecification;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;

import java.util.List;

public final class SqlLearningActivityAdapter {
    public static final String COURSE_ID = "builtin-data-management";
    public static final String SECTION_ID = "sql-practice";

    public LearningActivityDefinition adapt(ExerciseDefinition exercise, ExerciseDataset dataset) {
        String knowledgePointId = "legacy-sql:" + java.util.HexFormat.of().formatHex(
            exercise.knowledgePoint().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        return new LearningActivityDefinition(
            exercise.id(), COURSE_ID, SECTION_ID, exercise.title(), exercise.description(),
            List.of(knowledgePointId), ActivityDifficulty.valueOf(exercise.difficulty().name()),
            15, exercise.version(), exercise.enabled(),
            new SqlActivitySpecification(
                dataset, exercise.knowledgePoint(), exercise.referenceSql(),
                exercise.evaluationRule(), exercise.hints(), 1
            ),
            exercise.createdAt(), exercise.updatedAt()
        );
    }
}
