package com.sqlteacher.application.knowledge;

/** Orchestrates cloud knowledge-bundle update checks and downloads (v3.4.3 OKB-4). */
public interface KnowledgeBundleUpdateService {

    /** Compare the cloud manifest against the installed bundle state without downloading. */
    KnowledgeBundleUpdateStatus check();

    /** Download the cloud bundle (verified) and import it as a cloud-sourced bundle. */
    KnowledgeBundleImportReport downloadAndImport();

    /**
     * @param cloudAvailable false when the cloud publishes no bundle
     * @param updateAvailable true when a cloud bundle exists and is newer than the installed one
     */
    record KnowledgeBundleUpdateStatus(
        boolean cloudAvailable,
        boolean updateAvailable,
        String bundleId,
        String cloudVersion,
        String localVersion,
        String title,
        long sizeBytes,
        String message
    ) {
        public KnowledgeBundleUpdateStatus {
            bundleId = bundleId == null ? "" : bundleId;
            cloudVersion = cloudVersion == null ? "" : cloudVersion;
            localVersion = localVersion == null ? "" : localVersion;
            title = title == null ? "" : title;
            message = message == null ? "" : message;
        }
    }
}
