package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.knowledge.KnowledgeDocument;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeDetail;
import com.sqlteacher.application.knowledge.CourseKnowledgeRevision;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.domain.SqlTeacherException;

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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SqliteKnowledgeService implements KnowledgeDocumentService, KnowledgeSearchService, CourseKnowledgeService {
    static final long MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    static final int MAX_CHUNK_CHARACTERS = 800;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown");

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
                throw new IllegalArgumentException("Document must be between 1 byte and 2 MiB");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_READ_FAILED", "Failed to read knowledge document", error);
        }
        String content = decodeUtf8(bytes).replace("\u0000", "").trim();
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
    public CourseKnowledgeArticle importArticle(
        Path path,
        String requestedCourseTitle,
        String requestedSectionTitle,
        List<String> requestedKnowledgePoints
    ) {
        String courseTitle = requireText(requestedCourseTitle, "courseTitle");
        String sectionTitle = requireText(requestedSectionTitle, "sectionTitle");
        List<String> knowledgePoints = normalizeKnowledgePoints(requestedKnowledgePoints);
        KnowledgeDocument document = importDocument(path);
        String content = readContent(path);
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
        return getArticle(articleId).article();
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
    public List<KnowledgeSearchResult> search(
        String requestedQuery,
        CourseKnowledgeSearchFilter requestedFilter,
        int limit
    ) {
        CourseKnowledgeSearchFilter filter = requestedFilter == null
            ? CourseKnowledgeSearchFilter.allLocal() : requestedFilter;
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Knowledge search limit must be between 1 and 50");
        }
        Map<String, CourseKnowledgeArticle> byDocument = listArticles().stream()
            .collect(Collectors.toMap(CourseKnowledgeArticle::documentId, Function.identity()));
        return search(requestedQuery, 50).stream()
            .filter(result -> Optional.ofNullable(byDocument.get(result.documentId()))
                .filter(article -> matches(article, filter)).isPresent())
            .limit(limit)
            .toList();
    }

    private static boolean matches(CourseKnowledgeArticle article, CourseKnowledgeSearchFilter filter) {
        if (!filter.includePrivate() && article.visibility() != KnowledgeVisibility.PUBLISHED) {
            return false;
        }
        if (!filter.courseTitle().isBlank() && !article.courseTitle().equalsIgnoreCase(filter.courseTitle())) {
            return false;
        }
        if (!filter.sectionTitle().isBlank() && !article.sectionTitle().equalsIgnoreCase(filter.sectionTitle())) {
            return false;
        }
        return filter.knowledgePoint().isBlank() || article.knowledgePoints().stream()
            .anyMatch(point -> point.equalsIgnoreCase(filter.knowledgePoint()));
    }

    static List<String> chunk(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String clean = paragraph.replaceAll("[\\t ]+", " ").replaceAll("\\n+", "\n").trim();
            if (clean.isBlank()) {
                continue;
            }
            for (int offset = 0; offset < clean.length(); offset += MAX_CHUNK_CHARACTERS) {
                String part = clean.substring(offset, Math.min(clean.length(), offset + MAX_CHUNK_CHARACTERS));
                if (current.length() > 0 && current.length() + 2 + part.length() > MAX_CHUNK_CHARACTERS) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(part);
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
            throw new IllegalArgumentException("Only UTF-8 .txt, .md, and .markdown documents are supported");
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
                throw new IllegalArgumentException("Document must be between 1 byte and 2 MiB");
            }
            String content = decodeUtf8(Files.readAllBytes(path)).replace("\u0000", "").trim();
            if (content.isBlank()) {
                throw new IllegalArgumentException("Knowledge document must contain UTF-8 text");
            }
            return content;
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_DOCUMENT_READ_FAILED", "Failed to read knowledge document", error);
        }
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
        return String.join(" AND ", phrases);
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
