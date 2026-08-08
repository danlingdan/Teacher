package com.sqlteacher.application.runner;

public enum RunnerFailureReason {
    NONE,
    TOOLCHAIN_UNAVAILABLE,
    SANDBOX_UNAVAILABLE,
    COMPILE_ERROR,
    RUNTIME_ERROR,
    TIME_LIMIT,
    MEMORY_LIMIT,
    OUTPUT_LIMIT,
    CANCELLED,
    INTERNAL_ERROR
}
