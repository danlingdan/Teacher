package com.sqlteacher.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.KnowledgeAsset;
import com.sqlteacher.application.knowledge.KnowledgeBundleImportReport;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleState;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.application.knowledge.KnowledgeChunkRecord;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.mock.MockLearningEventService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import com.sqlteacher.infrastructure.database.SqliteKnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteKnowledgeBundleServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingVectorStore vectorStore = new RecordingVectorStore();
    private final StubIndexService indexService = new StubIndexService();

    @Test
    void shouldImportBundlePublishDocumentsCopyAssetsAndRecordState() throws Exception {
        SqliteKnowledgeBundleService service = newService();
        byte[] image = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Path zip = buildBundle("official-db-concepts", "1.0.0", "数据库系统概念", List.of(
            doc("chap1/intro", "# 引言\n\n数据库系统概述。", "第1部分 关系语言",
                List.of(asset("chap1/intro/fig.png", image))),
            doc("chap2/sql", "# SQL\n\nSELECT 语句。", "第1部分 关系语言", List.of())
        ));

        KnowledgeBundleImportReport report = service.importBundle(zip, KnowledgeBundleSource.MANUAL);

        assertEquals("official-db-concepts", report.bundleId());
        assertEquals("1.0.0", report.version());
        assertEquals(2, report.totalDocuments());
        assertEquals(2, report.importedDocuments());
        assertEquals(0, report.replacedDocuments());
        assertEquals(0, report.failedDocuments());
        assertFalse(report.failed());
        assertEquals(1, indexService.rebuildPendingCalls);

        // Both documents are present, PUBLISHED, and bundle-tagged.
        List<CourseKnowledgeArticle> articles = knowledgeService().listArticles();
        assertEquals(2, articles.size());
        assertTrue(articles.stream().allMatch(a -> a.visibility().name().equals("PUBLISHED")));

        Optional<KnowledgeBundleState> state = service.findBundleState("official-db-concepts");
        assertTrue(state.isPresent());
        assertEquals("1.0.0", state.get().version());
        assertEquals(KnowledgeBundleSource.MANUAL, state.get().source());

        // The image asset was copied under <dataDir>/knowledge-assets/<bundleId>/... and is readable.
        String articleId = articles.stream()
            .filter(a -> a.title().equals("引言")).findFirst().orElseThrow().id();
        KnowledgeAsset asset = service.readArticleAsset(articleId, "chap1/intro/fig.png");
        assertEquals("image/png", asset.contentType());
        assertArrayEquals(image, asset.data());
    }

    @Test
    void shouldReplaceBundleDocumentsOnReimportWithoutDuplicating() throws Exception {
        SqliteKnowledgeBundleService service = newService();
        Path v1 = buildBundle("official-db-concepts", "1.0.0", "数据库系统概念", List.of(
            doc("chap1/intro", "# 引言\n\n第一版内容。", "第1部分", List.of())));
        service.importBundle(v1, KnowledgeBundleSource.BUILTIN);
        assertEquals(1, knowledgeService().listArticles().size());

        Path v2 = buildBundle("official-db-concepts", "2.0.0", "数据库系统概念", List.of(
            doc("chap1/intro", "# 引言\n\n第二版内容更新。", "第1部分", List.of())));
        KnowledgeBundleImportReport report = service.importBundle(v2, KnowledgeBundleSource.CLOUD);

        assertEquals(1, report.replacedDocuments());
        assertEquals(0, report.importedDocuments());
        // Still exactly one article for the same doc id, now at version 2.0.0.
        List<CourseKnowledgeArticle> articles = knowledgeService().listArticles();
        assertEquals(1, articles.size());
        assertEquals("2.0.0", service.findBundleState("official-db-concepts").orElseThrow().version());
        assertEquals(KnowledgeBundleSource.CLOUD, service.findBundleState("official-db-concepts").orElseThrow().source());
        assertTrue(vectorStore.deletedArticleIds.size() >= 1);
    }

    @Test
    void shouldNeverTouchUserAuthoredDocuments() throws Exception {
        SqliteKnowledgeBundleService service = newService();
        // A user-authored article (bundle columns NULL).
        Path userDoc = tempDir.resolve("user-note.md");
        Files.writeString(userDoc, "# 用户笔记\n\n我自己的内容。", StandardCharsets.UTF_8);
        CourseKnowledgeArticle userArticle =
            knowledgeService().importArticle(userDoc, "我的课程", "随手记", List.of(), true).article();

        Path zip = buildBundle("official-db-concepts", "1.0.0", "数据库系统概念", List.of(
            doc("chap1/intro", "# 引言\n\n官方内容。", "第1部分", List.of())));
        service.importBundle(zip, KnowledgeBundleSource.MANUAL);
        assertEquals(2, knowledgeService().listArticles().size());

        // Re-import the bundle with a doc removed; the user article must survive untouched.
        Path zip2 = buildBundle("official-db-concepts", "1.1.0", "数据库系统概念", List.of(
            doc("chap1/intro", "# 引言\n\n官方内容修订。", "第1部分", List.of())));
        service.importBundle(zip2, KnowledgeBundleSource.CLOUD);

        List<CourseKnowledgeArticle> articles = knowledgeService().listArticles();
        assertEquals(2, articles.size());
        assertTrue(articles.stream().anyMatch(a -> a.id().equals(userArticle.id())));
    }

    @Test
    void shouldRejectChecksumMismatchWithoutLeavingState() throws Exception {
        SqliteKnowledgeBundleService service = newService();
        Path zip = buildBundleWithTamperedHash("official-db-concepts", "1.0.0", "数据库系统概念");

        SqlTeacherException error = assertThrows(SqlTeacherException.class,
            () -> service.importBundle(zip, KnowledgeBundleSource.MANUAL));
        assertEquals("KNOWLEDGE_BUNDLE_CHECKSUM_MISMATCH", error.errorCode());
        assertTrue(service.listBundleStates().isEmpty());
        assertTrue(knowledgeService().listArticles().isEmpty());
    }

    @Test
    void shouldRejectZipSlipEntries() throws Exception {
        SqliteKnowledgeBundleService service = newService();
        Path zip = tempDir.resolve("evil.zip");
        try (OutputStream fileOut = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fileOut)) {
            zos.putNextEntry(new ZipEntry("../escape.md"));
            zos.write("# 逃逸".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        SqlTeacherException error = assertThrows(SqlTeacherException.class,
            () -> service.importBundle(zip, KnowledgeBundleSource.MANUAL));
        assertEquals("KNOWLEDGE_BUNDLE_UNSAFE_PATH", error.errorCode());
    }

    @Test
    void shouldRefuseAssetPathTraversalAndNonBundleArticles() throws Exception {
        SqliteKnowledgeBundleService service = newService();
        Path userDoc = tempDir.resolve("user-note.md");
        Files.writeString(userDoc, "# 用户笔记\n\n内容。", StandardCharsets.UTF_8);
        CourseKnowledgeArticle userArticle =
            knowledgeService().importArticle(userDoc, "我的课程", "随手记", List.of(), true).article();

        // A user article has no bundle, so asset reads are refused.
        SqlTeacherException notBundled = assertThrows(SqlTeacherException.class,
            () -> service.readArticleAsset(userArticle.id(), "chap1/intro/fig.png"));
        assertEquals("KNOWLEDGE_ASSET_NOT_BUNDLED", notBundled.errorCode());
    }

    // ---- fixtures ----

    private SqliteKnowledgeService knowledgeServiceInstance;

    private SqliteKnowledgeBundleService newService() {
        DatabaseConfiguration databases =
            new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1),
                Duration.ofSeconds(1), "test"));
        new SqliteAppDatabaseInitializer(configuration).initialize();
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(databases);
        knowledgeServiceInstance = new SqliteKnowledgeService(
            connectionFactory, new MockLearningEventService(), () -> "guest");
        return new SqliteKnowledgeBundleService(
            connectionFactory, knowledgeServiceInstance, indexService, vectorStore, configuration);
    }

    private SqliteKnowledgeService knowledgeService() {
        return knowledgeServiceInstance;
    }

    private record DocSpec(String id, String content, String sectionTitle, List<AssetSpec> assets) {}
    private record AssetSpec(String relativePath, byte[] bytes) {}

    private static DocSpec doc(String id, String content, String sectionTitle, List<AssetSpec> assets) {
        return new DocSpec(id, content, sectionTitle, assets);
    }

    private static AssetSpec asset(String relativePath, byte[] bytes) {
        return new AssetSpec(relativePath, bytes);
    }

    private Path buildBundle(String bundleId, String version, String title, List<DocSpec> docs) throws Exception {
        return buildBundle(bundleId, version, title, docs, false);
    }

    private Path buildBundleWithTamperedHash(String bundleId, String version, String title) throws Exception {
        return buildBundle(bundleId, version, title,
            List.of(doc("chap1/intro", "# 引言\n\n内容。", "第1部分", List.of())), true);
    }

    private Path buildBundle(String bundleId, String version, String title,
                             List<DocSpec> docs, boolean tamperHash) throws Exception {
        Path zip = tempDir.resolve("bundle-" + version + "-" + System.nanoTime() + ".zip");
        List<Map<String, Object>> documents = new ArrayList<>();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (DocSpec spec : docs) {
            byte[] contentBytes = spec.content().getBytes(StandardCharsets.UTF_8);
            String docPath = "docs/" + spec.id() + ".md";
            entries.put(docPath, contentBytes);
            List<Map<String, String>> attachments = new ArrayList<>();
            for (AssetSpec asset : spec.assets()) {
                String assetPath = "attachments/" + asset.relativePath();
                entries.put(assetPath, asset.bytes());
                attachments.add(Map.of("path", assetPath, "sha256", sha256(asset.bytes())));
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", spec.id());
            document.put("path", docPath);
            document.put("title", spec.id());
            document.put("sectionTitle", spec.sectionTitle());
            document.put("sha256", tamperHash ? sha256("wrong".getBytes(StandardCharsets.UTF_8)) : sha256(contentBytes));
            document.put("attachments", attachments);
            documents.add(document);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", bundleId);
        manifest.put("version", version);
        manifest.put("title", title);
        manifest.put("documents", documents);
        entries.put("manifest.json", mapper.writeValueAsBytes(manifest));

        try (OutputStream fileOut = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fileOut)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static String sha256(byte[] bytes) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(bytes);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
    }

    private static final class StubIndexService implements KnowledgeIndexService {
        int rebuildPendingCalls = 0;

        @Override
        public IndexReport rebuildContent() {
            return rebuildPending();
        }

        @Override
        public IndexReport rebuildPending() {
            rebuildPendingCalls++;
            return new IndexReport(0, 0, "ok");
        }

        @Override
        public IndexReport rebuildAll() {
            return new IndexReport(0, 0, "ok");
        }

        @Override
        public IndexStatus status() {
            return new IndexStatus(0, 0, 0, "FTS5", "ok");
        }
    }

    private static final class RecordingVectorStore implements KnowledgeVectorStore {
        final List<String> deletedArticleIds = new ArrayList<>();

        @Override
        public void replaceRevision(String revisionId, List<KnowledgeChunkRecord> chunks, List<float[]> vectors) {
        }

        @Override
        public List<VectorSearchHit> search(float[] queryVector, CourseKnowledgeSearchFilter filter,
                                            String ownerId, int limit) {
            return List.of();
        }

        @Override
        public void deleteArticle(String articleId) {
            deletedArticleIds.add(articleId);
        }

        @Override
        public void clear() {
        }
    }
}
