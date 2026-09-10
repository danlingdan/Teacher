package com.sqlteacher.application.exercise;

import java.util.Objects;

/**
 * Draft explanation text produced by the AI chain. Purely display text: nothing in this
 * record may be written into learning state, and the UI must label it as AI-generated.
 */
public record ExerciseExplanation(String explanation, String model) {
    public ExerciseExplanation {
        explanation = Objects.requireNonNull(explanation, "explanation must not be null").strip();
        if (explanation.isEmpty()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
        model = Objects.requireNonNull(model, "model must not be null").trim();
    }
}
