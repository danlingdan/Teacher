package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.KnowledgeAsset;
import com.sqlteacher.application.knowledge.KnowledgeBundleImportReport;
import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleState;
import com.sqlteacher.application.knowledge.KnowledgeBundleSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBundleBootstrapServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNothingWhenDirectoryIsMissing() {
        FakeBundleService bundles = new FakeBundleService();
        KnowledgeBundleBootstrapService service =
            new KnowledgeBundleBootstrapService(bundles, tempDir.resolve("missing"));

        assertEquals(0, service.importBuiltinBundles());
        assertTrue(bundles.imports.isEmpty());
    }

    @Test
    void importsWhenNoStateExists() throws Exception {
        Path dir = bundleDirWith("official-db-concepts-1.0.0.zip");
        FakeBundleService bundles = new FakeBundleService();
        bundles.summary = new KnowledgeBundleSummary("official-db-concepts", "1.0.0", "数据库系统概念", 8);
        bundles.existingState = Optional.empty();
        KnowledgeBundleBootstrapService service = new KnowledgeBundleBootstrapService(bundles, dir);

        assertEquals(1, service.importBuiltinBundles());
        assertEquals(1, bundles.imports.size());
        assertEquals(KnowledgeBundleSource.BUILTIN, bundles.imports.get(0));
    }

    @Test
    void skipsWhenInstalledVersionIsNewer() throws Exception {
        Path dir = bundleDirWith("official-db-concepts-1.0.0.zip");
        FakeBundleService bundles = new FakeBundleService();
        bundles.summary = new KnowledgeBundleSummary("official-db-concepts", "1.0.0", "数据库系统概念", 8);
        bundles.existingState = Optional.of(new KnowledgeBundleState(
            "official-db-concepts", "2.0.0", KnowledgeBundleSource.CLOUD, "sha", Instant.now()));
        KnowledgeBundleBootstrapService service = new KnowledgeBundleBootstrapService(bundles, dir);

        assertEquals(0, service.importBuiltinBundles());
        assertTrue(bundles.imports.isEmpty());
    }

    @Test
    void upgradesWhenBundledVersionIsNewerThanInstalled() throws Exception {
        Path dir = bundleDirWith("official-db-concepts-3.0.0.zip");
        FakeBundleService bundles = new FakeBundleService();
        bundles.summary = new KnowledgeBundleSummary("official-db-concepts", "3.0.0", "数据库系统概念", 8);
        bundles.existingState = Optional.of(new KnowledgeBundleState(
            "official-db-concepts", "1.0.0", KnowledgeBundleSource.BUILTIN, "sha", Instant.now()));
        KnowledgeBundleBootstrapService service = new KnowledgeBundleBootstrapService(bundles, dir);

        assertEquals(1, service.importBuiltinBundles());
        assertEquals(KnowledgeBundleSource.BUILTIN, bundles.imports.get(0));
    }

    private Path bundleDirWith(String zipName) throws Exception {
        Path dir = tempDir.resolve("knowledge-" + zipName);
        Files.createDirectories(dir);
        Files.write(dir.resolve(zipName), "dummy".getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static final class FakeBundleService implements KnowledgeBundleService {
        KnowledgeBundleSummary summary = new KnowledgeBundleSummary("b", "1.0.0", "", 0);
        Optional<KnowledgeBundleState> existingState = Optional.empty();
        final List<KnowledgeBundleSource> imports = new ArrayList<>();

        @Override
        public KnowledgeBundleImportReport importBundle(Path bundleZip, KnowledgeBundleSource source) {
            imports.add(source);
            return new KnowledgeBundleImportReport(summary.bundleId(), summary.version(), source,
                summary.documentCount(), summary.documentCount(), 0, 0, Instant.now());
        }

        @Override
        public KnowledgeBundleSummary inspectBundle(Path bundleZip) {
            return summary;
        }

        @Override
        public List<KnowledgeBundleState> listBundleStates() {
            return existingState.map(List::of).orElse(List.of());
        }

        @Override
        public Optional<KnowledgeBundleState> findBundleState(String bundleId) {
            return existingState;
        }

        @Override
        public KnowledgeAsset readArticleAsset(String articleId, String relativePath) {
            throw new UnsupportedOperationException("not used in bootstrap");
        }
    }
}
