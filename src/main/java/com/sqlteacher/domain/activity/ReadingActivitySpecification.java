package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ReadingActivitySpecification(int formatVersion, String sourceTitle, String license,
                                           String content, List<ReadingCheck> checks, int passPercent)
        implements ActivitySpecification {
    public ReadingActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        sourceTitle = required(sourceTitle, "sourceTitle", 300);
        license = required(license, "license", 120);
        content = required(content, "content", 50_000);
        checks = List.copyOf(Objects.requireNonNull(checks, "checks must not be null"));
        if (checks.isEmpty() || checks.size() > 30
                || new HashSet<>(checks.stream().map(ReadingCheck::id).toList()).size() != checks.size()) {
            throw new IllegalArgumentException("reading checks are invalid");
        }
        if (passPercent < 1 || passPercent > 100) throw new IllegalArgumentException("passPercent is invalid");
    }

    @Override public ActivityType type() { return ActivityType.READING; }

    private static String required(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return normalized;
    }
}
