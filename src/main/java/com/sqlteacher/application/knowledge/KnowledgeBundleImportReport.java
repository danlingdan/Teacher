package com.sqlteacher.application.knowledge;

import java.time.Instant;

/** Outcome of importing an official knowledge bundle zip (v3.4.3 OKB-3). */
public record KnowledgeBundleImportReport(
    String bundleId,
    String version,
    KnowledgeBundleSource source,
    int totalDocuments,
    int importedDocuments,
    int replacedDocuments,
    int failedDocuments,
    Instant importedAt
) {
    public KnowledgeBundleImportReport {
        if (bundleId == null || bundleId.isBlank()) {
            throw new IllegalArgumentException("bundleId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (totalDocuments < 0 || importedDocuments < 0 || replacedDocuments < 0 || failedDocuments < 0) {
            throw new IllegalArgumentException("document counts must not be negative");
        }
        if (importedAt == null) {
            throw new IllegalArgumentException("importedAt must not be null");
        }
    }

    public boolean failed() {
        return failedDocuments > 0;
    }
}
