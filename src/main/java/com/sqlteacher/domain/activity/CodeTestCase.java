package com.sqlteacher.domain.activity;

import java.util.Objects;

public record CodeTestCase(String id, String input, String expectedOutput) {
    public CodeTestCase {
        id = required(id, "id");
        input = Objects.requireNonNull(input, "input must not be null");
        expectedOutput = Objects.requireNonNull(expectedOutput, "expectedOutput must not be null");
        if (input.length() > 64 * 1024 || expectedOutput.length() > 64 * 1024) {
            throw new IllegalArgumentException("code test case exceeds the 64 KiB content limit");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
