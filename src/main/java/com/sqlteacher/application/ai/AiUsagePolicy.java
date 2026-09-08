package com.sqlteacher.application.ai;

import java.time.Duration;

public record AiUsagePolicy(int maxInputCharacters, int maxOutputCharacters, Duration timeout) {
    public static AiUsagePolicy defaults() {
        return new AiUsagePolicy(24_000, 8_000, Duration.ofSeconds(45));
    }

    public AiUsagePolicy {
        if (maxInputCharacters < 1 || maxOutputCharacters < 1) {
            throw new IllegalArgumentException("AI usage limits must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
