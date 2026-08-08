package com.sqlteacher.domain.activity;

import java.util.Objects;

public record QuizOption(String id, String text) {
    public QuizOption {
        id = required(id, "id");
        text = required(text, "text");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
