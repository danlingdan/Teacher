package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.CloudKnowledgeBundleManifest;
import com.sqlteacher.application.knowledge.KnowledgeBundleImportReport;
import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleState;
import com.sqlteacher.application.knowledge.KnowledgeBundleCatalog;
import com.sqlteacher.application.knowledge.KnowledgeBundleUpdateService;
import com.sqlteacher.application.knowledge.KnowledgeBundleVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Cloud knowledge-bundle update orchestration (v3.4.3 OKB-4): checks the cloud manifest against
 * the installed state, and downloads + imports a newer bundle. Downloads are verified by the
 * catalog before import; the temporary file is always removed afterwards.
 */
public final class DefaultKnowledgeBundleUpdateService implements KnowledgeBundleUpdateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBundleUpdateService.class);

    private final KnowledgeBundleCatalog catalog;
    private final KnowledgeBundleService bundleService;

    public DefaultKnowledgeBundleUpdateService(KnowledgeBundleCatalog catalog, KnowledgeBundleService bundleService) {
        this.catalog = catalog;
        this.bundleService = bundleService;
    }

    @Override
    public KnowledgeBundleUpdateStatus check() {
        Optional<CloudKnowledgeBundleManifest> manifest = catalog.fetchManifest();
        if (manifest.isEmpty()) {
            return new KnowledgeBundleUpdateStatus(false, false, "", "", "", "", 0,
                "云端暂未提供官方知识库。");
        }
        CloudKnowledgeBundleManifest cloud = manifest.get();
        Optional<KnowledgeBundleState> local = bundleService.findBundleState(cloud.bundleId());
        String localVersion = local.map(KnowledgeBundleState::version).orElse("");
        boolean updateAvailable = KnowledgeBundleVersions.isNewer(cloud.version(), localVersion);
        String message = updateAvailable
            ? (localVersion.isBlank() ? "云端提供官方知识库，可下载安装。" : "云端有更新版本，可下载更新。")
            : "本地已是最新版本。";
        return new KnowledgeBundleUpdateStatus(true, updateAvailable, cloud.bundleId(),
            cloud.version(), localVersion, cloud.title(), cloud.sizeBytes(), message);
    }

    @Override
    public KnowledgeBundleImportReport downloadAndImport() {
        CloudKnowledgeBundleManifest cloud = catalog.fetchManifest()
            .orElseThrow(() -> new IllegalStateException("云端暂未提供官方知识库。"));
        Path download = catalog.downloadBundle(cloud);
        try {
            return bundleService.importBundle(download, KnowledgeBundleSource.CLOUD);
        } finally {
            try {
                Files.deleteIfExists(download);
            } catch (IOException error) {
                log.debug("Temporary knowledge bundle cleanup failed: {}", error.toString());
            }
        }
    }
}
