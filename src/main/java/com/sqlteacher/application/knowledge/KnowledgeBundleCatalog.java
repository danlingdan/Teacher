package com.sqlteacher.application.knowledge;

import java.nio.file.Path;
import java.util.Optional;

/** Remote source of the official knowledge bundle (v3.4.3 OKB-4). */
public interface KnowledgeBundleCatalog {

    /**
     * Fetch the cloud manifest. Empty means the cloud does not publish a bundle (a 404),
     * which is distinct from a network failure (thrown as a runtime exception).
     */
    Optional<CloudKnowledgeBundleManifest> fetchManifest();

    /**
     * Download the bundle to a temporary file, verifying size and sha256 against the manifest.
     * The caller owns the returned path and must delete it after import.
     */
    Path downloadBundle(CloudKnowledgeBundleManifest manifest);
}
