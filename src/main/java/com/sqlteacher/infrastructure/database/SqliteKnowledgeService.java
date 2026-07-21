package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.knowledge.KnowledgeDocument;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.domain.SqlTeacherException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class SqliteKnowledgeService implements KnowledgeDocumentService, KnowledgeSearchService {
    static final long MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    static final int MAX_CHUNK_CHARACTERS = 800;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown");

    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventService eventService;

    public SqliteKnowledgeService(JdbcConnectionFactory connectionFactory, LearningEventService eventService) {
        this.connectionFactory = connectionFactory;
        this.eventService = eventService;
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
        String content = new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "").trim();
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

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
