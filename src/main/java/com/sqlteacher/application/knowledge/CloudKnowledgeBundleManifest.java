package com.sqlteacher.application.knowledge;

/** Cloud-published official knowledge bundle manifest (v3.4.3 OKB-4). */
public record CloudKnowledgeBundleManifest(
    String bundleId,
    String version,
    String title,
    long sizeBytes,
    String sha256
) {
    public CloudKnowledgeBundleManifest {
        if (bundleId == null || bundleId.isBlank()) {
            throw new IllegalArgumentException("bundleId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 must not be blank");
        }
        title = title == null ? "" : title.trim();
    }
}
