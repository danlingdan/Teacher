package com.sqlteacher.application.knowledge;

import java.time.Instant;

/** Persisted state of an imported official knowledge bundle (knowledge_bundle_state row). */
public record KnowledgeBundleState(
    String bundleId,
    String version,
    KnowledgeBundleSource source,
    String archiveSha256,
    Instant importedAt
) {
    public KnowledgeBundleState {
        if (bundleId == null || bundleId.isBlank()) {
            throw new IllegalArgumentException("bundleId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (importedAt == null) {
            throw new IllegalArgumentException("importedAt must not be null");
        }
        archiveSha256 = archiveSha256 == null || archiveSha256.isBlank() ? null : archiveSha256.trim();
    }
}
