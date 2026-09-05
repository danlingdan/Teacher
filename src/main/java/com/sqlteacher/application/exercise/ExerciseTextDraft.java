package com.sqlteacher.application.exercise;

import java.util.Objects;

public record ExerciseTextDraft(String text, String model) {
    public ExerciseTextDraft {
        text = requireText(text, "text");
        model = requireText(model, "model");
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
