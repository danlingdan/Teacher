package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseType;

import java.util.Objects;

/**
 * One aggregated entry of the current owner's wrong-answer book: attempted but never
 * passed, with deterministic local statistics only (no AI, no cloud state).
 */
public record WrongBookItem(
    String exerciseId,
    String title,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    ExerciseType exerciseType,
    int attempts,
    Integer bestScore,
    String lastAttemptAt,
    String lastFeedback
) {
    public WrongBookItem {
        exerciseId = requireText(exerciseId, "exerciseId");
        title = requireText(title, "title");
        knowledgePoint = Objects.requireNonNull(knowledgePoint, "knowledgePoint must not be null").trim();
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        exerciseType = exerciseType == null ? ExerciseType.QUERY : exerciseType;
        attempts = Math.max(0, attempts);
        if (bestScore != null && (bestScore < 0 || bestScore > 100)) {
            throw new IllegalArgumentException("bestScore must be between 0 and 100");
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
