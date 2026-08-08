package com.sqlteacher.domain.activity;

import java.time.Duration;
import java.util.Objects;

public record CodeExecutionLimits(
    Duration wallTime,
    Duration cpuTime,
    long memoryBytes,
    long outputBytes,
    long workspaceBytes,
    int files,
    int processes
) {
    public static final long MEBIBYTE = 1024L * 1024L;

    public CodeExecutionLimits {
        wallTime = positive(wallTime, "wallTime");
        cpuTime = positive(cpuTime, "cpuTime");
        if (wallTime.compareTo(Duration.ofSeconds(30)) > 0
                || cpuTime.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("code execution time limit exceeds the local policy");
        }
        if (memoryBytes < 32 * MEBIBYTE || memoryBytes > 512 * MEBIBYTE) {
            throw new IllegalArgumentException("memoryBytes must be between 32 MiB and 512 MiB");
        }
        if (outputBytes < 1024 || outputBytes > MEBIBYTE) {
            throw new IllegalArgumentException("outputBytes must be between 1 KiB and 1 MiB");
        }
        if (workspaceBytes < MEBIBYTE || workspaceBytes > 64 * MEBIBYTE) {
            throw new IllegalArgumentException("workspaceBytes must be between 1 MiB and 64 MiB");
        }
        if (files < 8 || files > 256 || processes < 1 || processes > 32) {
            throw new IllegalArgumentException("file or process limit exceeds the local policy");
        }
    }

    public static CodeExecutionLimits defaults() {
        return new CodeExecutionLimits(Duration.ofSeconds(5), Duration.ofSeconds(3),
            256 * MEBIBYTE, 64 * 1024, 16 * MEBIBYTE, 64, 8);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
