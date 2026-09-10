package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseType;

/**
 * Practice-catalog entry: the exercise identity plus the current local owner's attempt
 * status, so the student can see what they have already solved without leaving the page.
 */
public record ExerciseCatalogItem(
    String id,
    String title,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    ExerciseType exerciseType,
    int version,
    int attempts,
    boolean passed,
    String lastAttemptAt,
    Integer bestScore
) {
    public ExerciseCatalogItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (knowledgePoint == null || knowledgePoint.isBlank()) {
            throw new IllegalArgumentException("knowledgePoint must not be blank");
        }
        difficulty = difficulty == null ? ExerciseDifficulty.BEGINNER : difficulty;
        exerciseType = exerciseType == null ? ExerciseType.QUERY : exerciseType;
        attempts = Math.max(0, attempts);
        if (bestScore != null && (bestScore < 0 || bestScore > 100)) {
            throw new IllegalArgumentException("bestScore must be between 0 and 100");
        }
    }
}
