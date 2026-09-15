package com.sqlteacher.application.knowledge;

/** Lightweight summary of a bundle zip's manifest, read without importing (v3.4.3 OKB-5). */
public record KnowledgeBundleSummary(
    String bundleId,
    String version,
    String title,
    int documentCount
) {
    public KnowledgeBundleSummary {
        if (bundleId == null || bundleId.isBlank()) {
            throw new IllegalArgumentException("bundleId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (documentCount < 0) {
            throw new IllegalArgumentException("documentCount must not be negative");
        }
        title = title == null ? "" : title.trim();
    }
}
