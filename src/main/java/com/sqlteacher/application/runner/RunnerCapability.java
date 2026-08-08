package com.sqlteacher.application.runner;

import com.sqlteacher.domain.activity.CodeLanguage;

import java.util.Objects;

public record RunnerCapability(CodeLanguage language, boolean available, String reasonCode) {
    public RunnerCapability {
        language = Objects.requireNonNull(language, "language must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null").trim();
        if (available && !reasonCode.isEmpty()) {
            throw new IllegalArgumentException("available capability cannot have a failure reason");
        }
        if (!available && reasonCode.isEmpty()) {
            throw new IllegalArgumentException("unavailable capability requires a reason code");
        }
    }
}
