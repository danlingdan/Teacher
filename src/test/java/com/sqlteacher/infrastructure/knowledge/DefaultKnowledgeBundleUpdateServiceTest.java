package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.CloudKnowledgeBundleManifest;
import com.sqlteacher.application.knowledge.KnowledgeAsset;
import com.sqlteacher.application.knowledge.KnowledgeBundleImportReport;
import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleState;
import com.sqlteacher.application.knowledge.KnowledgeBundleSummary;
import com.sqlteacher.application.knowledge.KnowledgeBundleCatalog;
import com.sqlteacher.application.knowledge.KnowledgeBundleUpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultKnowledgeBundleUpdateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void checkReportsCloudUnavailableWhenNoManifest() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.manifest = Optional.empty();
        var service = new DefaultKnowledgeBundleUpdateService(catalog, new FakeBundles(Optional.empty()));

        KnowledgeBundleUpdateService.KnowledgeBundleUpdateStatus status = service.check();

        assertFalse(status.cloudAvailable());
        assertFalse(status.updateAvailable());
        assertTrue(status.message().contains("云端"));
    }

    @Test
    void checkFlagsUpdateWhenCloudVersionIsNewer() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.manifest = Optional.of(new CloudKnowledgeBundleManifest(
            "official-db-concepts", "2.0.0", "数据库系统概念", 123, "sha"));
        var bundles = new FakeBundles(Optional.of(new KnowledgeBundleState(
            "official-db-concepts", "1.0.0", KnowledgeBundleSource.BUILTIN, "old", Instant.now())));
        var service = new DefaultKnowledgeBundleUpdateService(catalog, bundles);

        var status = service.check();

        assertTrue(status.cloudAvailable());
        assertTrue(status.updateAvailable());
        assertEquals("2.0.0", status.cloudVersion());
        assertEquals("1.0.0", status.localVersion());
    }

    @Test
    void checkReportsUpToDateWhenLocalIsNewerOrEqual() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.manifest = Optional.of(new CloudKnowledgeBundleManifest(
            "official-db-concepts", "1.0.0", "数据库系统概念", 123, "sha"));
        var bundles = new FakeBundles(Optional.of(new KnowledgeBundleState(
            "official-db-concepts", "1.0.0", KnowledgeBundleSource.CLOUD, "sha", Instant.now())));
        var service = new DefaultKnowledgeBundleUpdateService(catalog, bundles);

        assertFalse(service.check().updateAvailable());
    }

    @Test
    void downloadAndImportDownloadsAsCloudSourceAndDeletesTempFile() throws Exception {
        Path staged = tempDir.resolve("staged.zip");
        Files.write(staged, "bundle-bytes".getBytes(StandardCharsets.UTF_8));
        FakeCatalog catalog = new FakeCatalog();
        catalog.manifest = Optional.of(new CloudKnowledgeBundleManifest(
            "official-db-concepts", "2.0.0", "数据库系统概念", 12, "sha"));
        catalog.downloadPath = staged;
        FakeBundles bundles = new FakeBundles(Optional.empty());
        var service = new DefaultKnowledgeBundleUpdateService(catalog, bundles);

        KnowledgeBundleImportReport report = service.downloadAndImport();

        assertEquals(1, bundles.imports.size());
        assertEquals(KnowledgeBundleSource.CLOUD, bundles.imports.get(0));
        assertEquals("2.0.0", report.version());
        // The temporary download must be cleaned up after import.
        assertFalse(Files.exists(staged));
    }

    @Test
    void downloadAndImportFailsWhenCloudUnavailable() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.manifest = Optional.empty();
        var service = new DefaultKnowledgeBundleUpdateService(catalog, new FakeBundles(Optional.empty()));

        assertThrows(IllegalStateException.class, service::downloadAndImport);
    }

    private static final class FakeCatalog implements KnowledgeBundleCatalog {
        Optional<CloudKnowledgeBundleManifest> manifest = Optional.empty();
        Path downloadPath;

        @Override
        public Optional<CloudKnowledgeBundleManifest> fetchManifest() {
            return manifest;
        }

        @Override
        public Path downloadBundle(CloudKnowledgeBundleManifest m) {
            return downloadPath;
        }
    }

    private static final class FakeBundles implements KnowledgeBundleService {
        final Optional<KnowledgeBundleState> state;
        final List<KnowledgeBundleSource> imports = new java.util.ArrayList<>();

        FakeBundles(Optional<KnowledgeBundleState> state) {
            this.state = state;
        }

        @Override
        public KnowledgeBundleImportReport importBundle(Path bundleZip, KnowledgeBundleSource source) {
            imports.add(source);
            try {
                Files.deleteIfExists(bundleZip);
            } catch (Exception ignored) {
                // test cleanup
            }
            return new KnowledgeBundleImportReport("official-db-concepts", "2.0.0", source, 8, 8, 0, 0, Instant.now());
        }

        @Override
        public KnowledgeBundleSummary inspectBundle(Path bundleZip) {
            return new KnowledgeBundleSummary("official-db-concepts", "2.0.0", "", 8);
        }

        @Override
        public int removeBundle(String bundleId) {
            return 0;
        }

        @Override
        public List<KnowledgeBundleState> listBundleStates() {
            return state.map(List::of).orElse(List.of());
        }

        @Override
        public Optional<KnowledgeBundleState> findBundleState(String bundleId) {
            return state;
        }

        @Override
        public KnowledgeAsset readArticleAsset(String articleId, String relativePath) {
            throw new UnsupportedOperationException();
        }
    }
}
