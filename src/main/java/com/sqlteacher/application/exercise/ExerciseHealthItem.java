package com.sqlteacher.application.exercise;

import java.util.Objects;

/**
 * One row of the read-only teacher health check: the same deterministic self-test the
 * import runs, applied to the stored catalog without any side effects.
 */
public record ExerciseHealthItem(
    String exerciseId,
    String title,
    boolean passed,
    String message
) {
    public ExerciseHealthItem {
        exerciseId = Objects.requireNonNull(exerciseId, "exerciseId must not be null").trim();
        title = Objects.requireNonNull(title, "title must not be null").trim();
        message = Objects.requireNonNull(message, "message must not be null").trim();
        if (exerciseId.isEmpty()) {
            throw new IllegalArgumentException("exerciseId must not be blank");
        }
    }
}
