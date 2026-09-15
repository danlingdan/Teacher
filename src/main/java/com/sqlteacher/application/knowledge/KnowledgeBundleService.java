package com.sqlteacher.application.knowledge;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Imports and tracks official course-knowledge bundles (v3.4.3 OKB-3/OKB-7).
 *
 * <p>A bundle is a zip of {@code manifest.json + docs/** + attachments/**}. Import is an
 * owner-independent upsert keyed by {@code (bundleId, bundleDocId)}: each run replaces the
 * official documents it carries, marks them PUBLISHED, copies their images under
 * {@code <dataDirectory>/knowledge-assets/<bundleId>/}, and records the applied version in
 * {@code knowledge_bundle_state}. User-authored documents (bundle_id NULL) are never touched.
 */
public interface KnowledgeBundleService {

    /** Import (or re-import) a bundle zip. The zip must already be sha256-verified by the caller when it came from the cloud. */
    KnowledgeBundleImportReport importBundle(Path bundleZip, KnowledgeBundleSource source);

    /** Read a bundle zip's manifest summary without importing it. */
    KnowledgeBundleSummary inspectBundle(Path bundleZip);

    /** All imported bundle states, most recently imported first. */
    List<KnowledgeBundleState> listBundleStates();

    Optional<KnowledgeBundleState> findBundleState(String bundleId);

    /**
     * Resolve and read an image asset referenced by an article's markdown.
     *
     * @param articleId    the article whose bundle ownership scopes the lookup
     * @param relativePath asset path relative to the bundle asset root (e.g. {@code <docId>/<file>.png})
     */
    KnowledgeAsset readArticleAsset(String articleId, String relativePath);
}
