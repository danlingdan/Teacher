package com.sqlteacher.domain.activity;

import java.util.Objects;

public record CodeActivityArtifact(CodeLanguage language, String sourceCode) implements ActivityArtifact {
    public CodeActivityArtifact {
        language = Objects.requireNonNull(language, "language must not be null");
        sourceCode = Objects.requireNonNull(sourceCode, "sourceCode must not be null");
        if (sourceCode.isBlank()) throw new IllegalArgumentException("sourceCode must not be blank");
        if (sourceCode.length() > 256 * 1024) {
            throw new IllegalArgumentException("sourceCode exceeds the 256 KiB limit");
        }
    }

    @Override public ActivityType type() { return ActivityType.CODE; }
}
