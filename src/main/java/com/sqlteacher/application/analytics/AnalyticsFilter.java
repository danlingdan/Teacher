package com.sqlteacher.application.analytics;

import java.time.Instant;

public record AnalyticsFilter(
    Instant startInclusive,
    Instant endExclusive,
    String exerciseId,
    String knowledgePoint,
    String errorCode
) {
    public AnalyticsFilter {
        if (startInclusive != null && endExclusive != null && !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must be before endExclusive");
        }
        exerciseId = normalize(exerciseId);
        knowledgePoint = normalize(knowledgePoint);
        errorCode = normalize(errorCode);
    }

    public static AnalyticsFilter all() {
        return new AnalyticsFilter(null, null, null, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
