package com.sqlteacher.application.exercise;

import java.util.Objects;

public record ExerciseHint(int level, String text, boolean exhausted) {
    public ExerciseHint {
        if (level < 1 || level > 3) {
            throw new IllegalArgumentException("level must be between 1 and 3");
        }
        text = Objects.requireNonNull(text, "text must not be null").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
