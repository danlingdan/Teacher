package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDifficulty;

import java.util.List;
import java.util.Objects;

public record ExerciseImportPreview(
    List<DatasetPreview> datasets,
    List<ExercisePreview> exercises
) {
    public ExerciseImportPreview {
        datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets must not be null"));
        exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
    }

    public record DatasetPreview(String id, String name, SelfTestStatus selfTest) {
        public DatasetPreview {
            id = requireText(id, "id");
            name = requireText(name, "name");
            selfTest = Objects.requireNonNull(selfTest, "selfTest must not be null");
        }
    }

    public record ExercisePreview(
        String id,
        String title,
        String knowledgePoint,
        ExerciseDifficulty difficulty,
        SelfTestStatus selfTest
    ) {
        public ExercisePreview {
            id = requireText(id, "id");
            title = requireText(title, "title");
            knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
            difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
            selfTest = Objects.requireNonNull(selfTest, "selfTest must not be null");
        }
    }

    public record SelfTestStatus(boolean passed, String message) {
        public SelfTestStatus {
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
