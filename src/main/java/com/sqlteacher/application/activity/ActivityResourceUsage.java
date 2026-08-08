package com.sqlteacher.application.activity;

import java.time.Duration;
import java.util.Objects;

public record ActivityResourceUsage(Duration wallTime, long outputBytes, long filesCreated) {
    public ActivityResourceUsage {
        wallTime = Objects.requireNonNull(wallTime, "wallTime must not be null");
        if (wallTime.isNegative() || outputBytes < 0 || filesCreated < 0) {
            throw new IllegalArgumentException("resource usage must not be negative");
        }
    }

    public static ActivityResourceUsage evaluationOnly(Duration wallTime) {
        return new ActivityResourceUsage(wallTime, 0, 0);
    }
}
