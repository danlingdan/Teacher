package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.knowledge.KnowledgeDocument;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeDetail;
import com.sqlteacher.application.knowledge.CourseKnowledgeImportResult;
import com.sqlteacher.application.knowledge.CourseKnowledgeRevision;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.domain.SqlTeacherException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Collectors;

public final class SqliteKnowledgeService implements KnowledgeDocumentService, KnowledgeSearchService, CourseKnowledgeService {
    static final long MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;
    static final int MAX_CHUNK_CHARACTERS = 800;
    /** v3.6.0 KBQ-1: 分块算法版本号——算法或参数变化时提升，存量库启动后自动重切。 */
    public static final String CHUNKER_VERSION = "structure-aware-v1";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx");

    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventService eventService;
    private final LearningEventOwnerProvider ownerProvider;

    public SqliteKnowledgeService(JdbcConnectionFactory connectionFactory, LearningEventService eventService) {
        this(connectionFactory, eventService, () -> "guest");
    }

    public SqliteKnowledgeService(
        JdbcConnectionFactory connectionFactory,
        LearningEventService eventService,
        LearningEventOwnerProvider ownerProvider
    ) {
        this.connectionFactory = connectionFactory;
        this.eventService = eventService;
        this.ownerProvider = ownerProvider;
    }

    @Override
    public KnowledgeDocument importDocument(Path requestedPath) {
        Path path = validatePath(requestedPath);
        byte[] bytes;
        try {
            long size = Files.size(path);
            if (size < 1 || size > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("Document must be between 1 byte and 20 MiB");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_READ_FAILED", "Failed to read knowledge document", error);
        }
        String content = extractContent(path, bytes);
        if (content.isBlank()) {
            throw new IllegalArgumentException("Knowledge document must contain UTF-8 text");
        }
        List<String> chunks = chunk(content);
        String id = UUID.randomUUID().toString();
        String title = title(path, content);
        String sourceName = path.getFileName().toString();
        String hash = sha256(bytes);
        Instant importedAt = Instant.now();

        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                insertDocument(connection, id, title, sourceName, hash, chunks.size(), importedAt);
                insertChunks(connection, id, chunks);
                connection.commit();
                return new KnowledgeDocument(id, title, sourceName, chunks.size(), importedAt);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            if (error.getMessage() != null && error.getMessage().contains("knowledge_documents.content_hash")) {
                throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_DUPLICATE", "This knowledge document was already imported", error);
            }
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_IMPORT_FAILED", "Failed to import knowledge document", error);
        }
    }

    @Override
    public List<KnowledgeDocument> listDocuments() {
        String sql = """
            select id, title, source_name, chunk_count, imported_at
            from knowledge_documents
            order by imported_at desc, title
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<KnowledgeDocument> documents = new ArrayList<>();
            while (rows.next()) {
                documents.add(new KnowledgeDocument(
                    rows.getString("id"), rows.getString("title"), rows.getString("source_name"),
                    rows.getInt("chunk_count"), Instant.parse(rows.getString("imported_at"))
                ));
            }
            return List.copyOf(documents);
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_LIST_FAILED", "Failed to list knowledge documents", error);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        String id = requireText(documentId, "documentId");
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteChunks = connection.prepareStatement(
                "delete from knowledge_chunks where document_id = ?"
            ); PreparedStatement deleteDocument = connection.prepareStatement(
                "delete from knowledge_documents where id = ?"
            )) {
                deleteChunks.setString(1, id);
                deleteChunks.executeUpdate();
                deleteDocument.setString(1, id);
                if (deleteDocument.executeUpdate() != 1) {
                    throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document not found");
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_DELETE_FAILED", "Failed to delete knowledge document", error);
        }
    }

    @Override
    public List<KnowledgeSearchResult> search(String requestedQuery, int limit) {
        String query = requireText(requestedQuery, "query");
        if (query.length() > 200) {
            throw new IllegalArgumentException("Knowledge search query must not exceed 200 characters");
        }
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Knowledge search limit must be between 1 and 50");
        }
        String ftsQuery = toFtsQuery(query);
        String sql = """
            select d.id, d.title, d.source_name, c.chunk_index,
                snippet(knowledge_chunks_fts, 0, '【', '】', '…', 24) as matched_snippet,
                bm25(knowledge_chunks_fts) as score
            from knowledge_chunks_fts
            join knowledge_chunks c on c.rowid = knowledge_chunks_fts.rowid
            join knowledge_documents d on d.id = c.document_id
            where knowledge_chunks_fts match ?
            order by score, d.title, c.chunk_index
            limit ?
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ftsQuery);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<KnowledgeSearchResult> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(new KnowledgeSearchResult(
                        rows.getString("id"), rows.getString("title"), rows.getString("source_name"),
                        rows.getInt("chunk_index"), rows.getString("matched_snippet"),
                        Math.max(0, -rows.getDouble("score"))
                    ));
                }
                List<KnowledgeSearchResult> snapshot = List.copyOf(results);
                eventService.recordKnowledgeSearch(query.length(), snapshot.size());
                return snapshot;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_SEARCH_FAILED", "Failed to search local knowledge", error);
        }
    }

    @Override
    public CourseKnowledgeImportResult importArticle(
        Path path,
        String requestedCourseTitle,
        String requestedSectionTitle,
        List<String> requestedKnowledgePoints,
        boolean allowDuplicate
    ) {
        String courseTitle = requireText(requestedCourseTitle, "courseTitle");
        String sectionTitle = requireText(requestedSectionTitle, "sectionTitle");
        List<String> knowledgePoints = normalizeKnowledgePoints(requestedKnowledgePoints);
        String content = readContent(validatePath(path));
        if (!allowDuplicate) {
            // v3.6.0 KBF-3：跨来源同内容导入过去会静默并存，先查重并把决定权交给用户。
            List<CourseKnowledgeImportResult.ContentDuplicate> duplicates = findContentDuplicates(content);
            if (!duplicates.isEmpty()) {
                return new CourseKnowledgeImportResult(null, duplicates);
            }
        }
        KnowledgeDocument document = importDocument(path);
        String articleId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        String contentHash = sha256(content.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                insertArticle(connection, articleId, document.id(), currentOwnerId(), courseTitle, sectionTitle, now);
                insertRevision(connection, revisionId, articleId, 1, document.title(), content, contentHash,
                    document.sourceName(), headingPath(content), now);
                insertKnowledgePoints(connection, revisionId, knowledgePoints);
                insertHybridChunks(connection, document.id(), articleId, revisionId, chunk(content),
                    String.join("\n", headingPath(content)));
                enqueueIndexJob(connection, articleId, revisionId, now);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException | RuntimeException error) {
            try {
                deleteDocument(document.id());
            } catch (RuntimeException ignored) {
                error.addSuppressed(ignored);
            }
            if (error instanceof SqlTeacherException sqlTeacherException) {
                throw sqlTeacherException;
            }
            throw new SqlTeacherException("COURSE_KNOWLEDGE_IMPORT_FAILED", "Failed to import course knowledge", error);
        }
        return CourseKnowledgeImportResult.imported(getArticle(articleId).article());
    }

    /**
     * v3.6.0 KBF-3: 在现存文章的当前修订中按归一化内容哈希查重（BOM/CRLF/连续空行收敛后
     * 比较，同一内容经不同来源导入也能命中）。扫描量为全部当前修订，仅在导入时执行。
     */
    private List<CourseKnowledgeImportResult.ContentDuplicate> findContentDuplicates(String content) {
        String hash = sha256(normalizeForDuplicateCheck(content).getBytes(StandardCharsets.UTF_8));
        String sql = """
            select a.id, a.course_title, a.section_title, a.current_revision, r.title, r.content
            from course_knowledge_articles a
            join course_knowledge_revisions r
              on r.article_id = a.id and r.revision = a.current_revision
            """;
        List<CourseKnowledgeImportResult.ContentDuplicate> matches = new ArrayList<>();
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String candidate = sha256(normalizeForDuplicateCheck(rows.getString("content"))
                    .getBytes(StandardCharsets.UTF_8));
                if (candidate.equals(hash)) {
                    matches.add(new CourseKnowledgeImportResult.ContentDuplicate(
                        rows.getString("id"), rows.getString("title"),
                        rows.getString("course_title"), rows.getString("section_title"),
                        rows.getInt("current_revision")));
                }
            }
            return List.copyOf(matches);
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_IMPORT_FAILED", "Failed to check duplicate content", error);
        }
    }

    static String normalizeForDuplicateCheck(String content) {
        return content.replace("\uFEFF", "")
            .replace("\r\n", "\n").replace('\r', '\n')
            .trim()
            .replaceAll("\\n{3,}", "\n\n");
    }

    @Override
    public List<CourseKnowledgeArticle> listArticles() {
        String sql = """
            select a.id, a.document_id, a.owner_id, a.course_title, a.section_title, a.visibility,
                a.current_revision, a.updated_at, r.id revision_id, r.title, r.content_hash
            from course_knowledge_articles a
            join course_knowledge_revisions r
              on r.article_id = a.id and r.revision = a.current_revision
            where a.owner_id = ? or a.visibility = 'PUBLISHED'
            order by a.updated_at desc, r.title
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currentOwnerId());
            List<CourseKnowledgeArticle> articles = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    articles.add(toArticle(connection, rows));
                }
            }
            return List.copyOf(articles);
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_LIST_FAILED", "Failed to list course knowledge", error);
        }
    }

    @Override
    public CourseKnowledgeDetail getArticle(String requestedArticleId) {
        String articleId = requireText(requestedArticleId, "articleId");
        String sql = """
            select a.id, a.document_id, a.owner_id, a.course_title, a.section_title, a.visibility,
                a.current_revision, a.updated_at, r.id revision_id, r.title, r.content_hash
            from course_knowledge_articles a
            join course_knowledge_revisions r
              on r.article_id = a.id and r.revision = a.current_revision
            where a.id = ? and (a.owner_id = ? or a.visibility = 'PUBLISHED')
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, articleId);
            statement.setString(2, currentOwnerId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SqlTeacherException("COURSE_KNOWLEDGE_NOT_FOUND", "Course knowledge article not found");
                }
                CourseKnowledgeArticle article = toArticle(connection, rows);
                boolean ownArticle = currentOwnerId().equals(rows.getString("owner_id"));
                List<CourseKnowledgeRevision> history = loadRevisions(connection, articleId);
                CourseKnowledgeRevision current = history.stream()
                    .filter(revision -> revision.revision() == article.currentRevision())
                    .findFirst()
                    .orElseThrow(() -> new SqlTeacherException("COURSE_KNOWLEDGE_REVISION_MISSING", "Current revision is missing"));
                return new CourseKnowledgeDetail(article, current, ownArticle ? history : List.of(current));
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_READ_FAILED", "Failed to read course knowledge", error);
        }
    }

    @Override
    public CourseKnowledgeArticle reviseArticle(
        String requestedArticleId,
        Path path,
        List<String> requestedKnowledgePoints
    ) {
        String articleId = requireText(requestedArticleId, "articleId");
        Path validatedPath = validatePath(path);
        String content = readContent(validatedPath);
        List<String> chunks = chunk(content);
        List<String> knowledgePoints = normalizeKnowledgePoints(requestedKnowledgePoints);
        String title = title(validatedPath, content);
        String sourceName = validatedPath.getFileName().toString();
        String hash = sha256(content.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                ArticleState state = loadArticleState(connection, articleId, currentOwnerId());
                int nextRevision = state.currentRevision() + 1;
                String revisionId = UUID.randomUUID().toString();
                try (PreparedStatement deleteChunks = connection.prepareStatement("delete from knowledge_chunks where document_id = ?")) {
                    deleteChunks.setString(1, state.documentId());
                    deleteChunks.executeUpdate();
                }
                try (PreparedStatement updateDocument = connection.prepareStatement("""
                    update knowledge_documents
                    set title = ?, source_name = ?, content_hash = ?, chunk_count = ?, imported_at = ?
                    where id = ?
                    """)) {
                    updateDocument.setString(1, title);
                    updateDocument.setString(2, sourceName);
                    updateDocument.setString(3, hash);
                    updateDocument.setInt(4, chunks.size());
                    updateDocument.setString(5, now.toString());
                    updateDocument.setString(6, state.documentId());
                    updateDocument.executeUpdate();
                }
                insertChunks(connection, state.documentId(), chunks);
                insertRevision(connection, revisionId, articleId, nextRevision, title, content, hash,
                    sourceName, headingPath(content), now);
                insertKnowledgePoints(connection, revisionId, knowledgePoints);
                insertHybridChunks(connection, state.documentId(), articleId, revisionId, chunks,
                    String.join("\n", headingPath(content)));
                enqueueIndexJob(connection, articleId, revisionId, now);
                try (PreparedStatement updateArticle = connection.prepareStatement("""
                    update course_knowledge_articles
                    set current_revision = ?, visibility = 'PRIVATE', updated_at = ?
                    where id = ? and owner_id = ?
                    """)) {
                    updateArticle.setInt(1, nextRevision);
                    updateArticle.setString(2, now.toString());
                    updateArticle.setString(3, articleId);
                    updateArticle.setString(4, currentOwnerId());
                    updateArticle.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_REVISE_FAILED", "Failed to revise course knowledge", error);
        }
        return getArticle(articleId).article();
    }

    @Override
    public CourseKnowledgeArticle changeVisibility(String requestedArticleId, KnowledgeVisibility visibility) {
        String articleId = requireText(requestedArticleId, "articleId");
        if (visibility == null) {
            throw new IllegalArgumentException("visibility must not be null");
        }
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 update course_knowledge_articles set visibility = ?, updated_at = ? where id = ? and owner_id = ?
                 """)) {
            statement.setString(1, visibility.name());
            statement.setString(2, Instant.now().toString());
            statement.setString(3, articleId);
            statement.setString(4, currentOwnerId());
            if (statement.executeUpdate() != 1) {
                throw new SqlTeacherException("COURSE_KNOWLEDGE_NOT_FOUND", "Course knowledge article not found");
            }
            return getArticle(articleId).article();
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_VISIBILITY_FAILED", "Failed to update course knowledge visibility", error);
        }
    }

    @Override
    public void deleteArticle(String requestedArticleId) {
        String articleId = requireText(requestedArticleId, "articleId");
        String documentId;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(
                 "select document_id from course_knowledge_articles where id = ?")) {
            statement.setString(1, articleId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SqlTeacherException("COURSE_KNOWLEDGE_NOT_FOUND", "Course knowledge article not found");
                }
                documentId = rows.getString("document_id");
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_DELETE_FAILED", "Failed to delete course knowledge", error);
        }
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                // 应用库连接外键是关闭的（与官方包 hardDeleteArticle 同一前提），按子表在先显式删除，
                // 不得依赖 on delete cascade。knowledge_chunks 删除会触发 FTS after-delete 触发器；
                // 底层文档行可能已被旧版删除路径先行清掉，缺失时按 0 行处理。
                deleteByColumn(connection, "delete from knowledge_chunks_v2 where article_id = ?", articleId);
                deleteByColumn(connection, "delete from knowledge_index_jobs where article_id = ?", articleId);
                deleteByColumn(connection, "delete from knowledge_read_state where article_id = ?", articleId);
                deleteByColumn(connection, "delete from course_knowledge_point_links where revision_id in "
                    + "(select id from course_knowledge_revisions where article_id = ?)", articleId);
                deleteByColumn(connection, "delete from course_knowledge_revisions where article_id = ?", articleId);
                deleteByColumn(connection, "delete from course_knowledge_articles where id = ?", articleId);
                deleteByColumn(connection, "delete from knowledge_chunks where document_id = ?", documentId);
                deleteByColumn(connection, "delete from knowledge_documents where id = ?", documentId);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_DELETE_FAILED", "Failed to delete course knowledge", error);
        }
    }

    private static void deleteByColumn(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            statement.executeUpdate();
        }
    }

    /**
     * v3.6.0 KBQ-1: 用当前分块算法重切全部文章的当前修订（FTS 与混合分块表同步重写、
     * 索引作业重置为待处理）。启动时的分块版本升级与「重建检索索引」共用此路径；
     * 无可检索内容的文章跳过，不阻塞整体升级。
     */
    public int rechunkAllArticles() {
        record ArticleContent(String articleId, String documentId, String revisionId, String content) {}
        String sql = """
            select a.id, a.document_id, r.id revision_id, r.content
            from course_knowledge_articles a
            join course_knowledge_revisions r
              on r.article_id = a.id and r.revision = a.current_revision
            """;
        List<ArticleContent> articles = new ArrayList<>();
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                articles.add(new ArticleContent(rows.getString("id"), rows.getString("document_id"),
                    rows.getString("revision_id"), rows.getString("content")));
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("COURSE_KNOWLEDGE_RECHUNK_FAILED",
                "Failed to read articles for re-chunking", error);
        }
        int processed = 0;
        for (ArticleContent article : articles) {
            List<String> chunks;
            try {
                chunks = chunk(article.content());
            } catch (IllegalArgumentException skipped) {
                continue;
            }
            String headings = String.join("\n", headingPath(article.content()));
            try (Connection connection = connectionFactory.open("app")) {
                connection.setAutoCommit(false);
                try {
                    deleteByColumn(connection, "delete from knowledge_chunks where document_id = ?", article.documentId());
                    deleteByColumn(connection, "delete from knowledge_chunks_v2 where article_id = ?", article.articleId());
                    try (PreparedStatement resetJobs = connection.prepareStatement(
                        "update knowledge_index_jobs set status = 'PENDING', error_message = null, updated_at = ? where article_id = ?")) {
                        resetJobs.setString(1, Instant.now().toString());
                        resetJobs.setString(2, article.articleId());
                        resetJobs.executeUpdate();
                    }
                    insertChunks(connection, article.documentId(), chunks);
                    insertHybridChunks(connection, article.documentId(), article.articleId(),
                        article.revisionId(), chunks, headings);
                    connection.commit();
                    processed++;
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    throw error;
                }
            } catch (SQLException error) {
                throw new SqlTeacherException("COURSE_KNOWLEDGE_RECHUNK_FAILED",
                    "Failed to re-chunk article " + article.articleId(), error);
            }
        }
        return processed;
    }

    @Override
    public List<KnowledgeSearchResult> search(
        String requestedQuery,
        CourseKnowledgeSearchFilter requestedFilter,
        int limit,
        int offset
    ) {
        CourseKnowledgeSearchFilter filter = requestedFilter == null
            ? CourseKnowledgeSearchFilter.allLocal() : requestedFilter;
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Knowledge search limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Knowledge search offset must not be negative");
        }
        String andQuery = toFtsQuery(requestedQuery);
        // v3.6.0 KBQ-3：owner/visibility/课程/章节/知识点过滤全部下推到 SQL（过去先取
        // 50 条再内存过滤，窄过滤会欠召回）；AND 无命中时放宽为 OR + bm25 排序兜底
        // （unicode61 无中文分词，硬 AND 对多词中文查询过脆）。安全过滤始终留在 SQL 层。
        List<KnowledgeSearchResult> results = filteredFtsSearch(andQuery, filter, limit, offset);
        if (results.isEmpty() && andQuery.contains(" AND ")) {
            results = filteredFtsSearch(toFtsQuery(requestedQuery, " OR "), filter, limit, offset);
        }
        List<KnowledgeSearchResult> snapshot = List.copyOf(results);
        eventService.recordKnowledgeSearch(requestedQuery.trim().length(), snapshot.size());
        return snapshot;
    }

    private List<KnowledgeSearchResult> filteredFtsSearch(
        String ftsQuery,
        CourseKnowledgeSearchFilter filter,
        int limit,
        int offset
    ) {
        StringBuilder sql = new StringBuilder("""
            select d.id, d.title, d.source_name, c.chunk_index,
                snippet(knowledge_chunks_fts, 0, '【', '】', '…', 24) as matched_snippet,
                bm25(knowledge_chunks_fts) as score
            from knowledge_chunks_fts
            join knowledge_chunks c on c.rowid = knowledge_chunks_fts.rowid
            join knowledge_documents d on d.id = c.document_id
            join course_knowledge_articles a on a.document_id = d.id
            where knowledge_chunks_fts match ?
            """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(ftsQuery);
        if (filter.includePrivate()) {
            sql.append(" and (a.owner_id = ? or a.visibility = 'PUBLISHED')");
            parameters.add(currentOwnerId());
        } else {
            sql.append(" and a.visibility = 'PUBLISHED'");
        }
        if (!filter.courseTitle().isBlank()) {
            sql.append(" and lower(a.course_title) = lower(?)");
            parameters.add(filter.courseTitle());
        }
        if (!filter.sectionTitle().isBlank()) {
            sql.append(" and lower(a.section_title) = lower(?)");
            parameters.add(filter.sectionTitle());
        }
        if (!filter.knowledgePoint().isBlank()) {
            sql.append("""
                and exists (select 1 from course_knowledge_point_links l
                    join course_knowledge_revisions cr on cr.id = l.revision_id
                    where cr.article_id = a.id and cr.revision = a.current_revision
                      and lower(l.knowledge_point) = lower(?))
                """);
            parameters.add(filter.knowledgePoint());
        }
        sql.append(" order by score, d.title, c.chunk_index limit ? offset ?");
        parameters.add(limit);
        parameters.add(offset);
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            List<KnowledgeSearchResult> results = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(new KnowledgeSearchResult(
                        rows.getString("id"), rows.getString("title"), rows.getString("source_name"),
                        rows.getInt("chunk_index"), rows.getString("matched_snippet"),
                        Math.max(0, -rows.getDouble("score"))
                    ));
                }
            }
            return results;
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_SEARCH_FAILED", "Failed to search local knowledge", error);
        }
    }

    /**
     * v3.6.0 KBQ-1: 结构感知分块——标题处开新块（分块边界对齐章节），围栏代码块与表格
     * 不从中间截断，超长块按行边界二次切分。分块算法变化时必须同步提升 {@link #CHUNKER_VERSION}
     * 触发存量库自动重切。
     */
    static List<String> chunk(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : structuralBlocks(normalized)) {
            if (isHeadingBlock(block) && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            for (String piece : splitOversize(block)) {
                if (current.length() > 0 && current.length() + 2 + piece.length() > MAX_CHUNK_CHARACTERS) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Knowledge document has no searchable content");
        }
        return List.copyOf(chunks);
    }

    /** 按结构把正文拆为最小块：标题行、围栏代码块、表格行组、空行分隔的段落。 */
    private static List<String> structuralBlocks(String normalized) {
        List<String> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<String> table = new ArrayList<>();
        List<String> code = new ArrayList<>();
        boolean inCode = false;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.replaceAll("[\t ]+", " ").trim();
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    code.add(line);
                    blocks.add(String.join("\n", code));
                    code.clear();
                    inCode = false;
                } else {
                    flushText(paragraph, blocks);
                    flushText(table, blocks);
                    code.add(line);
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.add(line);
                continue;
            }
            if (trimmed.startsWith("|")) {
                flushText(paragraph, blocks);
                table.add(trimmed);
                continue;
            }
            if (!table.isEmpty()) {
                flushText(table, blocks);
            }
            if (trimmed.isBlank()) {
                flushText(paragraph, blocks);
                continue;
            }
            if (isHeadingBlock(trimmed)) {
                flushText(paragraph, blocks);
                blocks.add(trimmed);
                continue;
            }
            paragraph.add(trimmed);
        }
        flushText(paragraph, blocks);
        flushText(table, blocks);
        if (!code.isEmpty()) {
            // 未闭合的围栏按普通内容处理，避免整块丢失。
            blocks.add(String.join("\n", code));
        }
        return blocks;
    }

    private static void flushText(List<String> lines, List<String> blocks) {
        if (!lines.isEmpty()) {
            blocks.add(String.join("\n", lines));
            lines.clear();
        }
    }

    private static boolean isHeadingBlock(String block) {
        return block.startsWith("#") && block.matches("#{1,6} .*");
    }

    /** 超过单块上限的块按行边界二次切分；单行超限时按字符硬切，行完整性优先。 */
    private static List<String> splitOversize(String block) {
        if (block.length() <= MAX_CHUNK_CHARACTERS) {
            return List.of(block);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (line.length() > MAX_CHUNK_CHARACTERS) {
                if (piece.length() > 0) {
                    pieces.add(piece.toString());
                    piece.setLength(0);
                }
                for (int offset = 0; offset < line.length(); offset += MAX_CHUNK_CHARACTERS) {
                    pieces.add(line.substring(offset, Math.min(line.length(), offset + MAX_CHUNK_CHARACTERS)));
                }
                continue;
            }
            if (piece.length() > 0 && piece.length() + 1 + line.length() > MAX_CHUNK_CHARACTERS) {
                pieces.add(piece.toString());
                piece.setLength(0);
            }
            if (piece.length() > 0) {
                piece.append('\n');
            }
            piece.append(line);
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }
        return List.copyOf(pieces);
    }

    private static Path validatePath(Path requestedPath) {
        if (requestedPath == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path path = requestedPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Knowledge document must be a regular file");
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only UTF-8 text, Markdown, PDF, and DOCX documents are supported");
        }
        return path;
    }

    private static void insertDocument(
        Connection connection,
        String id,
        String title,
        String sourceName,
        String hash,
        int chunkCount,
        Instant importedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into knowledge_documents(id, title, source_name, content_hash, chunk_count, imported_at)
            values (?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, id);
            statement.setString(2, title);
            statement.setString(3, sourceName);
            statement.setString(4, hash);
            statement.setInt(5, chunkCount);
            statement.setString(6, importedAt.toString());
            statement.executeUpdate();
        }
    }

    private static void insertChunks(Connection connection, String documentId, List<String> chunks) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into knowledge_chunks(id, document_id, chunk_index, content) values (?, ?, ?, ?)
            """)) {
            for (int index = 0; index < chunks.size(); index++) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, documentId);
                statement.setInt(3, index);
                statement.setString(4, chunks.get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertHybridChunks(
        Connection connection,
        String documentId,
        String articleId,
        String revisionId,
        List<String> chunks,
        String headingPath
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into knowledge_chunks_v2(
                id, document_id, article_id, revision_id, chunk_index, heading_path,
                start_offset, end_offset, token_count, content, content_hash, index_status
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            """)) {
            int offset = 0;
            for (int index = 0; index < chunks.size(); index++) {
                String value = chunks.get(index);
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, documentId);
                statement.setString(3, articleId);
                statement.setString(4, revisionId);
                statement.setInt(5, index);
                statement.setString(6, headingPath == null ? "" : headingPath);
                statement.setInt(7, offset);
                statement.setInt(8, offset + value.length());
                statement.setInt(9, Math.max(1, (value.length() + 3) / 4));
                statement.setString(10, value);
                statement.setString(11, sha256(value.getBytes(StandardCharsets.UTF_8)));
                statement.addBatch();
                offset += value.length();
            }
            statement.executeBatch();
        }
    }

    private static void enqueueIndexJob(Connection connection, String articleId, String revisionId, Instant now)
        throws SQLException {
        try (PreparedStatement supersede = connection.prepareStatement("""
                 update knowledge_index_jobs set status = 'COMPLETED', error_message = 'SUPERSEDED', updated_at = ?
                 where article_id = ? and status in ('PENDING','RUNNING')
                 """);
             PreparedStatement statement = connection.prepareStatement("""
            insert into knowledge_index_jobs(id, article_id, revision_id, status, created_at, updated_at)
            values (?, ?, ?, 'PENDING', ?, ?)
            """)) {
            supersede.setString(1, now.toString());
            supersede.setString(2, articleId);
            supersede.executeUpdate();
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, articleId);
            statement.setString(3, revisionId);
            statement.setString(4, now.toString());
            statement.setString(5, now.toString());
            statement.executeUpdate();
        }
    }

    private static void insertArticle(
        Connection connection,
        String articleId,
        String documentId,
        String ownerId,
        String courseTitle,
        String sectionTitle,
        Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into course_knowledge_articles(
                id, document_id, owner_id, course_title, section_title, visibility, current_revision, created_at, updated_at
            ) values (?, ?, ?, ?, ?, 'PRIVATE', 1, ?, ?)
            """)) {
            statement.setString(1, articleId);
            statement.setString(2, documentId);
            statement.setString(3, ownerId);
            statement.setString(4, courseTitle);
            statement.setString(5, sectionTitle);
            statement.setString(6, now.toString());
            statement.setString(7, now.toString());
            statement.executeUpdate();
        }
    }

    private static void insertRevision(
        Connection connection,
        String revisionId,
        String articleId,
        int revision,
        String title,
        String content,
        String contentHash,
        String sourceName,
        List<String> headings,
        Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into course_knowledge_revisions(
                id, article_id, revision, title, content, content_hash, source_name, heading_path, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, revisionId);
            statement.setString(2, articleId);
            statement.setInt(3, revision);
            statement.setString(4, title);
            statement.setString(5, content);
            statement.setString(6, contentHash);
            statement.setString(7, sourceName);
            statement.setString(8, String.join("\n", headings));
            statement.setString(9, now.toString());
            statement.executeUpdate();
        }
    }

    private static void insertKnowledgePoints(
        Connection connection,
        String revisionId,
        List<String> knowledgePoints
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into course_knowledge_point_links(revision_id, knowledge_point) values (?, ?)
            """)) {
            for (String point : knowledgePoints) {
                statement.setString(1, revisionId);
                statement.setString(2, point);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static CourseKnowledgeArticle toArticle(Connection connection, ResultSet rows) throws SQLException {
        return new CourseKnowledgeArticle(
            rows.getString("id"),
            rows.getString("document_id"),
            rows.getString("course_title"),
            rows.getString("section_title"),
            rows.getString("title"),
            KnowledgeVisibility.valueOf(rows.getString("visibility")),
            rows.getInt("current_revision"),
            loadKnowledgePoints(connection, rows.getString("revision_id")),
            rows.getString("content_hash"),
            Instant.parse(rows.getString("updated_at"))
        );
    }

    private static List<String> loadKnowledgePoints(Connection connection, String revisionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select knowledge_point from course_knowledge_point_links
            where revision_id = ? order by knowledge_point
            """)) {
            statement.setString(1, revisionId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> points = new ArrayList<>();
                while (rows.next()) {
                    points.add(rows.getString("knowledge_point"));
                }
                return List.copyOf(points);
            }
        }
    }

    private static List<CourseKnowledgeRevision> loadRevisions(Connection connection, String articleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select id, article_id, revision, title, content, content_hash, source_name, heading_path, created_at
            from course_knowledge_revisions where article_id = ? order by revision desc
            """)) {
            statement.setString(1, articleId);
            try (ResultSet rows = statement.executeQuery()) {
                List<CourseKnowledgeRevision> revisions = new ArrayList<>();
                while (rows.next()) {
                    String headingPath = rows.getString("heading_path");
                    revisions.add(new CourseKnowledgeRevision(
                        rows.getString("id"), rows.getString("article_id"), rows.getInt("revision"),
                        rows.getString("title"), rows.getString("content"), rows.getString("content_hash"),
                        rows.getString("source_name"), headingPath.isBlank() ? List.of() : headingPath.lines().toList(),
                        Instant.parse(rows.getString("created_at"))
                    ));
                }
                return List.copyOf(revisions);
            }
        }
    }

    private static ArticleState loadArticleState(Connection connection, String articleId, String ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select document_id, current_revision from course_knowledge_articles where id = ? and owner_id = ?
            """)) {
            statement.setString(1, articleId);
            statement.setString(2, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SqlTeacherException("COURSE_KNOWLEDGE_NOT_FOUND", "Course knowledge article not found");
                }
                return new ArticleState(rows.getString("document_id"), rows.getInt("current_revision"));
            }
        }
    }

    private static List<String> normalizeKnowledgePoints(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        LinkedHashSet<String> points = requested.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (points.stream().anyMatch(value -> value.length() > 120)) {
            throw new IllegalArgumentException("Knowledge point must not exceed 120 characters");
        }
        return List.copyOf(points);
    }

    private static String readContent(Path requestedPath) {
        Path path = validatePath(requestedPath);
        try {
            long size = Files.size(path);
            if (size < 1 || size > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("Document must be between 1 byte and 20 MiB");
            }
            String content = extractContent(path, Files.readAllBytes(path));
            if (content.isBlank()) {
                throw new IllegalArgumentException("Knowledge document must contain UTF-8 text");
            }
            return content;
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_READ_FAILED", "Failed to read knowledge document", error);
        }
    }

    private static String extractContent(Path path, byte[] bytes) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            String content;
            if (name.endsWith(".pdf")) {
                try (var document = Loader.loadPDF(bytes)) {
                    if (document.isEncrypted()) throw new IllegalArgumentException("Encrypted PDF documents are not supported");
                    content = new PDFTextStripper().getText(document);
                }
            } else if (name.endsWith(".docx")) {
                content = extractDocx(bytes);
            } else {
                content = decodeUtf8(bytes);
            }
            content = content.replace("\u0000", "").replace("\r\n", "\n").trim();
            if (content.isBlank()) throw new IllegalArgumentException("Knowledge document has no readable text");
            return content;
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_PARSE_FAILED", "Failed to parse knowledge document", error);
        }
    }

    private static String extractDocx(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if ("word/document.xml".equals(entry.getName())) {
                    byte[] xmlBytes = zip.readNBytes((int) Math.min(MAX_DOCUMENT_BYTES + 1, Integer.MAX_VALUE));
                    if (xmlBytes.length > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("DOCX text payload is too large");
                    String xml = decodeUtf8(xmlBytes).replaceAll("(?i)</w:p>", "\n").replaceAll("(?i)<w:tab[^>]*/>", "\t");
                    return Jsoup.parse(xml).text().trim();
                }
            }
        }
        throw new IllegalArgumentException("DOCX document.xml is missing");
    }

    private static List<String> headingPath(String content) {
        return content.lines()
            .map(String::trim)
            .filter(line -> line.matches("^#{1,6}\\s+.+"))
            .map(line -> truncate(line.replaceFirst("^#+\\s*", "").trim(), 160))
            .limit(24)
            .toList();
    }

    private static String title(Path path, String content) {
        for (String line : content.lines().limit(20).toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceFirst("^#+\\s*", "").trim();
                if (!heading.isBlank()) {
                    return truncate(heading, 160);
                }
            }
        }
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return truncate(dot > 0 ? fileName.substring(0, dot) : fileName, 160);
    }

    private static String toFtsQuery(String query) {
        return toFtsQuery(query, " AND ");
    }

    private static String toFtsQuery(String query, String operator) {
        String[] tokens = query.trim().split("\\s+");
        List<String> phrases = new ArrayList<>();
        for (String token : tokens) {
            String safe = token.replace("\"", "\"\"").trim();
            if (!safe.isBlank()) {
                phrases.add("\"" + safe + "\"");
            }
        }
        if (phrases.isEmpty()) {
            throw new IllegalArgumentException("query must contain searchable text");
        }
        return String.join(operator, phrases);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Knowledge document must use valid UTF-8", error);
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String currentOwnerId() {
        return requireText(ownerProvider.currentOwnerId(), "ownerId");
    }

    private record ArticleState(String documentId, int currentRevision) {
    }
}
