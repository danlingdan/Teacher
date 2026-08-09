package com.sqlteacher.domain.activity;

import java.util.Objects;

public record ReadingCheck(String id, String prompt, String expectedAnswer, String explanation) {
    public ReadingCheck {
        id = required(id, "id", 64);
        prompt = required(prompt, "prompt", 500);
        expectedAnswer = required(expectedAnswer, "expectedAnswer", 300);
        explanation = required(explanation, "explanation", 1_000);
    }

    private static String required(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return normalized;
    }
}
