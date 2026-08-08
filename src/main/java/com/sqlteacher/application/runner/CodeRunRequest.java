package com.sqlteacher.application.runner;

import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;

import java.util.Objects;

public record CodeRunRequest(
    CodeLanguage language,
    String sourceCode,
    String standardInput,
    CodeExecutionLimits limits
) {
    public CodeRunRequest {
        language = Objects.requireNonNull(language, "language must not be null");
        sourceCode = Objects.requireNonNull(sourceCode, "sourceCode must not be null");
        standardInput = Objects.requireNonNull(standardInput, "standardInput must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        if (sourceCode.isBlank() || sourceCode.length() > 256 * 1024) {
            throw new IllegalArgumentException("sourceCode must contain at most 256 KiB");
        }
        if (standardInput.length() > 64 * 1024) {
            throw new IllegalArgumentException("standardInput exceeds the 64 KiB limit");
        }
    }
}
