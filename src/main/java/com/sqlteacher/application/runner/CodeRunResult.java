package com.sqlteacher.application.runner;

import com.sqlteacher.application.activity.ActivityResourceUsage;

import java.util.Objects;

public record CodeRunResult(
    RunnerFailureReason failureReason,
    int exitCode,
    String standardOutput,
    String standardError,
    ActivityResourceUsage resourceUsage
) {
    public CodeRunResult {
        failureReason = Objects.requireNonNull(failureReason, "failureReason must not be null");
        standardOutput = Objects.requireNonNull(standardOutput, "standardOutput must not be null");
        standardError = Objects.requireNonNull(standardError, "standardError must not be null");
        resourceUsage = Objects.requireNonNull(resourceUsage, "resourceUsage must not be null");
        if (failureReason == RunnerFailureReason.NONE && exitCode != 0) {
            throw new IllegalArgumentException("successful run must have exit code zero");
        }
    }

    public boolean succeeded() {
        return failureReason == RunnerFailureReason.NONE;
    }
}
