package com.sqlteacher.application.analytics;

import java.time.Instant;

public record AnalyticsCsvExport(String fileName, String utf8Content, Instant generatedAt) {
    public AnalyticsCsvExport {
        if (fileName == null || fileName.isBlank() || utf8Content == null || generatedAt == null) {
            throw new IllegalArgumentException("CSV export values must not be null or blank");
        }
    }
}
