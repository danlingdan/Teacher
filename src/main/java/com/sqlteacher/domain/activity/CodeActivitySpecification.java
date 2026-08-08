package com.sqlteacher.domain.activity;

import java.util.List;
import java.util.Objects;

public record CodeActivitySpecification(
    int formatVersion,
    CodeLanguage language,
    String prompt,
    String starterCode,
    List<CodeTestCase> tests,
    CodeExecutionLimits limits
) implements ActivitySpecification {
    public CodeActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        language = Objects.requireNonNull(language, "language must not be null");
        prompt = required(prompt, "prompt");
        starterCode = Objects.requireNonNull(starterCode, "starterCode must not be null");
        tests = List.copyOf(Objects.requireNonNull(tests, "tests must not be null"));
        if (tests.isEmpty() || tests.size() > 32) {
            throw new IllegalArgumentException("code activity must contain between 1 and 32 tests");
        }
        if (starterCode.length() > 256 * 1024) {
            throw new IllegalArgumentException("starterCode exceeds the 256 KiB limit");
        }
        limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    @Override public ActivityType type() { return ActivityType.CODE; }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
