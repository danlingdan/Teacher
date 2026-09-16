package com.sqlteacher.infrastructure.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeAsset;
import com.sqlteacher.application.knowledge.KnowledgeBundleImportReport;
import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleState;
import com.sqlteacher.application.knowledge.KnowledgeBundleSummary;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SQLite-backed official knowledge bundle importer (v3.4.3 OKB-3/OKB-7).
 *
 * <p>Import is owner-independent: existing bundle documents are hard-deleted (the app database
 * runs with foreign keys off, so rows are removed child-first explicitly) then re-imported via
 * {@link CourseKnowledgeService}, marked PUBLISHED and tagged with {@code (bundle_id, bundle_doc_id)}.
 * Images are copied under {@code <dataDirectory>/knowledge-assets/<bundleId>/}.
 */
public final class SqliteKnowledgeBundleService implements KnowledgeBundleService {

    private static final Logger log = LoggerFactory.getLogger(SqliteKnowledgeBundleService.class);
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_ASSET_BYTES = 6L * 1024 * 1024;

    private final JdbcConnectionFactory connectionFactory;
    private final CourseKnowledgeService courseKnowledgeService;
    private final KnowledgeIndexService indexService;
    private final KnowledgeVectorStore vectorStore;
    private final Path assetRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqliteKnowledgeBundleService(
        JdbcConnectionFactory connectionFactory,
        CourseKnowledgeService courseKnowledgeService,
        KnowledgeIndexService indexService,
        KnowledgeVectorStore vectorStore,
        SqlTeacherConfiguration configuration
    ) {
        this.connectionFactory = connectionFactory;
        this.courseKnowledgeService = courseKnowledgeService;
        this.indexService = indexService;
        this.vectorStore = vectorStore;
        this.assetRoot = configuration.dataDirectory().resolve("knowledge-assets");
    }

    @Override
    public KnowledgeBundleImportReport importBundle(Path bundleZip, KnowledgeBundleSource source) {
        if (bundleZip == null || !Files.isRegularFile(bundleZip)) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_NOT_FOUND", "知识库文件不存在");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        String archiveSha256;
        try {
            archiveSha256 = sha256Hex(Files.readAllBytes(bundleZip));
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_READ_FAILED", "知识库文件读取失败", error);
        }

        Path tempRoot;
        try {
            tempRoot = Files.createTempDirectory("sqlteacher-bundle-");
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_READ_FAILED", "知识库解压失败", error);
        }
        try {
            unzip(bundleZip, tempRoot);
            Manifest manifest = readManifest(tempRoot.resolve("manifest.json"));
            verifyDocumentHashes(tempRoot, manifest);

            int imported = 0;
            int replaced = 0;
            int failed = 0;
            for (ManifestDocument document : manifest.documents()) {
                try {
                    Path docPath = resolveInside(tempRoot, document.path());
                    Optional<ExistingArticle> existing = findExistingArticle(manifest.bundleId(), document.id());
                    if (existing.isPresent()) {
                        hardDeleteArticle(existing.get().articleId(), existing.get().documentId());
                    }
                    CourseKnowledgeArticle article = courseKnowledgeService.importArticle(
                        docPath, manifest.title(), document.sectionTitle(), List.of());
                    markBundleArticle(article.id(), manifest.bundleId(), document.id());
                    copyAttachments(tempRoot, manifest.bundleId(), document);
                    if (existing.isPresent()) {
                        replaced++;
                    } else {
                        imported++;
                    }
                } catch (RuntimeException error) {
                    failed++;
                    log.warn("Knowledge bundle document import failed, bundleId={}, docId={}: {}",
                        manifest.bundleId(), document.id(), error.toString());
                }
            }
            upsertBundleState(manifest.bundleId(), manifest.version(), source, archiveSha256);
            indexService.rebuildPending();
            return new KnowledgeBundleImportReport(
                manifest.bundleId(), manifest.version(), source,
                manifest.documents().size(), imported, replaced, failed, Instant.now());
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    @Override
    public KnowledgeBundleSummary inspectBundle(Path bundleZip) {
        if (bundleZip == null || !Files.isRegularFile(bundleZip)) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_NOT_FOUND", "知识库文件不存在");
        }
        try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(bundleZip))) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    Manifest manifest = mapper.readValue(stream, Manifest.class);
                    if (manifest.bundleId() == null || manifest.bundleId().isBlank()
                        || manifest.version() == null || manifest.version().isBlank()) {
                        throw new SqlTeacherException("KNOWLEDGE_BUNDLE_MANIFEST_INVALID", "知识库 manifest 字段不完整");
                    }
                    int count = manifest.documents() == null ? 0 : manifest.documents().size();
                    return new KnowledgeBundleSummary(manifest.bundleId(), manifest.version(), manifest.title(), count);
                }
            }
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_READ_FAILED", "知识库 manifest 读取失败", error);
        }
        throw new SqlTeacherException("KNOWLEDGE_BUNDLE_MANIFEST_MISSING", "知识库缺少 manifest.json");
    }

    @Override
    public List<KnowledgeBundleState> listBundleStates() {
        String sql = "select bundle_id, version, source, archive_sha256, imported_at "
            + "from knowledge_bundle_state order by imported_at desc, bundle_id";
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<KnowledgeBundleState> states = new ArrayList<>();
            while (rows.next()) {
                states.add(toState(rows));
            }
            return List.copyOf(states);
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_STATE_READ_FAILED", "知识库状态读取失败", error);
        }
    }

    @Override
    public Optional<KnowledgeBundleState> findBundleState(String bundleId) {
        if (bundleId == null || bundleId.isBlank()) {
            return Optional.empty();
        }
        String sql = "select bundle_id, version, source, archive_sha256, imported_at "
            + "from knowledge_bundle_state where bundle_id = ?";
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bundleId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toState(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_STATE_READ_FAILED", "知识库状态读取失败", error);
        }
    }

    @Override
    public int removeBundle(String bundleId) {
        if (bundleId == null || bundleId.isBlank()) {
            throw new IllegalArgumentException("bundleId must not be blank");
        }
        List<String[]> doomed;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(
                 "select id, document_id from course_knowledge_articles "
                     + "where bundle_id = ? order by id")) {
            statement.setString(1, bundleId);
            try (ResultSet rows = statement.executeQuery()) {
                doomed = new ArrayList<>();
                while (rows.next()) {
                    doomed.add(new String[]{rows.getString(1), rows.getString(2)});
                }
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_READ_FAILED", "读取知识库文章失败", error);
        }
        for (String[] article : doomed) {
            hardDeleteArticle(article[0], article[1]);
        }
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(
                 "delete from knowledge_bundle_state where bundle_id = ?")) {
            statement.setString(1, bundleId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_STATE_DELETE_FAILED", "知识库状态删除失败", error);
        }
        // 拷贝出的图片资产可由包 zip 原样重建，随包一并移除。
        deleteRecursively(assetRoot.resolve(bundleId));
        indexService.rebuildPending();
        return doomed.size();
    }

    @Override
    public KnowledgeAsset readArticleAsset(String articleId, String relativePath) {
        if (articleId == null || articleId.isBlank() || relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("articleId and relativePath are required");
        }
        String bundleId = findArticleBundleId(articleId)
            .orElseThrow(() -> new SqlTeacherException(
                "KNOWLEDGE_ASSET_NOT_BUNDLED", "该文档没有随官方知识库分发的图片"));
        Path root = assetRoot.resolve(bundleId).normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new SqlTeacherException("KNOWLEDGE_ASSET_UNSAFE_PATH", "非法的图片路径");
        }
        String contentType = imageContentType(target.getFileName().toString())
            .orElseThrow(() -> new SqlTeacherException("KNOWLEDGE_ASSET_UNSUPPORTED", "仅支持读取图片附件"));
        if (!Files.isRegularFile(target)) {
            throw new SqlTeacherException("KNOWLEDGE_ASSET_NOT_FOUND", "图片附件不存在");
        }
        try {
            long size = Files.size(target);
            if (size < 1 || size > MAX_ASSET_BYTES) {
                throw new SqlTeacherException("KNOWLEDGE_ASSET_TOO_LARGE", "图片附件超出大小上限");
            }
            return new KnowledgeAsset(contentType, Files.readAllBytes(target));
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_ASSET_READ_FAILED", "图片附件读取失败", error);
        }
    }

    // ---- import helpers ----

    private void unzip(Path zip, Path tempRoot) {
        long totalBytes = 0;
        int entries = 0;
        try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = stream.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new SqlTeacherException("KNOWLEDGE_BUNDLE_TOO_LARGE", "知识库条目过多");
                }
                Path target = resolveInside(tempRoot, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long written = 0;
                try (OutputStream out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = stream.read(buffer)) > 0) {
                        written += read;
                        totalBytes += read;
                        if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_TOO_LARGE", "知识库解压后过大");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                if (written < 1) {
                    throw new SqlTeacherException("KNOWLEDGE_BUNDLE_EMPTY_ENTRY", "知识库包含空文件: " + entry.getName());
                }
            }
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_READ_FAILED", "知识库解压失败", error);
        }
    }

    private Manifest readManifest(Path manifestPath) {
        if (!Files.isRegularFile(manifestPath)) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_MANIFEST_MISSING", "知识库缺少 manifest.json");
        }
        try {
            Manifest manifest = mapper.readValue(manifestPath.toFile(), Manifest.class);
            if (manifest.bundleId() == null || manifest.bundleId().isBlank()
                || manifest.version() == null || manifest.version().isBlank()
                || manifest.documents() == null || manifest.documents().isEmpty()) {
                throw new SqlTeacherException("KNOWLEDGE_BUNDLE_MANIFEST_INVALID", "知识库 manifest 字段不完整");
            }
            return manifest;
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_MANIFEST_INVALID", "知识库 manifest 解析失败", error);
        }
    }

    private void verifyDocumentHashes(Path tempRoot, Manifest manifest) {
        for (ManifestDocument document : manifest.documents()) {
            Path docPath = resolveInside(tempRoot, document.path());
            if (!Files.isRegularFile(docPath)) {
                throw new SqlTeacherException("KNOWLEDGE_BUNDLE_DOC_MISSING", "知识库缺少文档: " + document.path());
            }
            try {
                String actual = sha256Hex(Files.readAllBytes(docPath));
                if (!actual.equalsIgnoreCase(document.sha256())) {
                    throw new SqlTeacherException("KNOWLEDGE_BUNDLE_CHECKSUM_MISMATCH",
                        "知识库文档校验失败: " + document.path());
                }
            } catch (IOException error) {
                throw new SqlTeacherException("KNOWLEDGE_BUNDLE_READ_FAILED", "知识库文档读取失败", error);
            }
        }
    }

    private void copyAttachments(Path tempRoot, String bundleId, ManifestDocument document) {
        if (document.attachments() == null) {
            return;
        }
        Path root = assetRoot.resolve(bundleId).normalize();
        for (ManifestAttachment attachment : document.attachments()) {
            // attachment.path is "attachments/<docId>/<file>"; mirror the part after "attachments/".
            String relative = attachment.path().replaceFirst("^attachments/", "");
            Path source = resolveInside(tempRoot, attachment.path());
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                throw new SqlTeacherException("KNOWLEDGE_BUNDLE_UNSAFE_PATH", "非法的附件路径: " + attachment.path());
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                throw new SqlTeacherException("KNOWLEDGE_ASSET_WRITE_FAILED", "图片附件写入失败", error);
            }
        }
    }

    private Optional<ExistingArticle> findExistingArticle(String bundleId, String bundleDocId) {
        String sql = "select id, document_id from course_knowledge_articles "
            + "where bundle_id = ? and bundle_doc_id = ? limit 1";
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bundleId);
            statement.setString(2, bundleDocId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                    ? Optional.of(new ExistingArticle(rows.getString("id"), rows.getString("document_id")))
                    : Optional.empty();
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_STATE_READ_FAILED", "知识库文章查询失败", error);
        }
    }

    private void markBundleArticle(String articleId, String bundleId, String bundleDocId) {
        String sql = "update course_knowledge_articles "
            + "set bundle_id = ?, bundle_doc_id = ?, visibility = 'PUBLISHED' where id = ?";
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bundleId);
            statement.setString(2, bundleDocId);
            statement.setString(3, articleId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_MARK_FAILED", "知识库文章标记失败", error);
        }
    }

    private void hardDeleteArticle(String articleId, String documentId) {
        // The app database runs with foreign keys off, so remove the article tree child-first.
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection, "delete from knowledge_chunks_v2 where article_id = ?", articleId);
                executeUpdate(connection, "delete from knowledge_index_jobs where article_id = ?", articleId);
                executeUpdate(connection,
                    "delete from course_knowledge_point_links where revision_id in "
                        + "(select id from course_knowledge_revisions where article_id = ?)", articleId);
                executeUpdate(connection, "delete from course_knowledge_revisions where article_id = ?", articleId);
                executeUpdate(connection, "delete from course_knowledge_articles where id = ?", articleId);
                // Deleting knowledge_chunks fires the FTS after-delete trigger.
                executeUpdate(connection, "delete from knowledge_chunks where document_id = ?", documentId);
                executeUpdate(connection, "delete from knowledge_documents where id = ?", documentId);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_DELETE_FAILED", "旧版官方文档删除失败", error);
        }
        vectorStore.deleteArticle(articleId);
    }

    private static void executeUpdate(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            statement.executeUpdate();
        }
    }

    private void upsertBundleState(String bundleId, String version, KnowledgeBundleSource source, String archiveSha256) {
        String sql = "insert into knowledge_bundle_state(bundle_id, version, source, archive_sha256, imported_at) "
            + "values (?, ?, ?, ?, ?) "
            + "on conflict(bundle_id) do update set version = excluded.version, source = excluded.source, "
            + "archive_sha256 = excluded.archive_sha256, imported_at = excluded.imported_at";
        String importedAt = Instant.now().toString();
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bundleId);
            statement.setString(2, version);
            statement.setString(3, source.wireName());
            statement.setString(4, archiveSha256);
            statement.setString(5, importedAt);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_STATE_WRITE_FAILED", "知识库状态写入失败", error);
        }
    }

    private Optional<String> findArticleBundleId(String articleId) {
        String sql = "select bundle_id from course_knowledge_articles where id = ?";
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, articleId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String bundleId = rows.getString("bundle_id");
                return bundleId == null || bundleId.isBlank() ? Optional.empty() : Optional.of(bundleId);
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_STATE_READ_FAILED", "知识库文章查询失败", error);
        }
    }

    private static KnowledgeBundleState toState(ResultSet rows) throws SQLException {
        String sha256 = rows.getString("archive_sha256");
        return new KnowledgeBundleState(
            rows.getString("bundle_id"),
            rows.getString("version"),
            KnowledgeBundleSource.fromWireName(rows.getString("source")),
            sha256,
            Instant.parse(rows.getString("imported_at"))
        );
    }

    private static Path resolveInside(Path root, String relativeName) {
        Path target = root.resolve(relativeName).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new SqlTeacherException("KNOWLEDGE_BUNDLE_UNSAFE_PATH", "知识库包含越界路径: " + relativeName);
        }
        return target;
    }

    private static Optional<String> imageContentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return Optional.of("image/png");
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return Optional.of("image/jpeg");
        }
        if (lower.endsWith(".gif")) {
            return Optional.of("image/gif");
        }
        if (lower.endsWith(".webp")) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort temp cleanup.
                }
            });
        } catch (IOException error) {
            log.debug("Temporary bundle directory cleanup failed: {}", error.toString());
        }
    }

    private record ExistingArticle(String articleId, String documentId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(String bundleId, String version, String title, List<ManifestDocument> documents) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ManifestDocument(
        String id, String path, String title, String sectionTitle, String sha256,
        List<ManifestAttachment> attachments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ManifestAttachment(String path, String sha256) {}
}
