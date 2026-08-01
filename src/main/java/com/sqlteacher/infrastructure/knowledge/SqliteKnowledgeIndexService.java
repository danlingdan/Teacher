package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.EmbeddingProvider;
import com.sqlteacher.application.knowledge.KnowledgeChunkRecord;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SqliteKnowledgeIndexService implements KnowledgeIndexService {
    private static final int EMBEDDING_BATCH_SIZE = 64;
    private final JdbcConnectionFactory connectionFactory;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeVectorStore vectorStore;

    public SqliteKnowledgeIndexService(
        JdbcConnectionFactory connectionFactory,
        EmbeddingProvider embeddingProvider,
        KnowledgeVectorStore vectorStore
    ) {
        this.connectionFactory = connectionFactory;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
    }

    @Override
    public synchronized IndexReport rebuildPending() {
        List<IndexJob> jobs = jobs("PENDING");
        int indexed = 0;
        int failed = 0;
        for (IndexJob job : jobs) {
            try {
                markJob(job.id(), "RUNNING", null);
                List<KnowledgeChunkRecord> chunks = loadChunks(job.revisionId());
                if (chunks.isEmpty()) {
                    markJob(job.id(), "COMPLETED", null);
                    continue;
                }
                List<float[]> vectors = new ArrayList<>();
                EmbeddingProvider.EmbeddingBatch lastBatch = null;
                for (int offset = 0; offset < chunks.size(); offset += EMBEDDING_BATCH_SIZE) {
                    List<String> content = chunks.subList(offset, Math.min(offset + EMBEDDING_BATCH_SIZE, chunks.size()))
                        .stream().map(KnowledgeChunkRecord::content).toList();
                    lastBatch = embeddingProvider.embed(content);
                    vectors.addAll(lastBatch.vectors());
                }
                vectorStore.replaceRevision(job.revisionId(), chunks, vectors);
                complete(job, chunks, lastBatch);
                indexed += chunks.size();
            } catch (RuntimeException error) {
                failed++;
                fail(job, safeMessage(error));
            }
        }
        return new IndexReport(indexed, failed, failed == 0
            ? "本地向量索引已更新。" : "部分索引任务失败，关键词检索仍可使用。");
    }

    @Override
    public synchronized IndexReport rebuildAll() {
        vectorStore.clear();
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement chunks = connection.prepareStatement("update knowledge_chunks_v2 set index_status = 'PENDING'");
             PreparedStatement jobs = connection.prepareStatement("""
                 update knowledge_index_jobs set status = 'PENDING', error_message = null, updated_at = ?
                 """)) {
            chunks.executeUpdate();
            jobs.setString(1, Instant.now().toString());
            jobs.executeUpdate();
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_RESET_FAILED", "Failed to reset knowledge index", error);
        }
        return rebuildPending();
    }

    @Override
    public IndexStatus status() {
        String sql = """
            select
                (select count(*) from knowledge_index_jobs where status in ('PENDING','RUNNING')) pending_jobs,
                (select count(*) from knowledge_chunks_v2 where index_status = 'INDEXED') indexed_chunks,
                (select count(*) from knowledge_chunks_v2 where index_status = 'FAILED') failed_chunks
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            int pending = rows.getInt("pending_jobs");
            int indexed = rows.getInt("indexed_chunks");
            int failed = rows.getInt("failed_chunks");
            String mode = indexed > 0 ? "HYBRID" : "FTS5";
            String message = failed > 0 ? "向量索引有失败项，已自动降级。" : pending > 0 ? "有待处理索引任务。" : "索引已就绪。";
            return new IndexStatus(pending, indexed, failed, mode, message);
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_STATUS_FAILED", "Failed to read knowledge index status", error);
        }
    }

    private List<IndexJob> jobs(String status) {
        String sql = """
            select j.id, j.article_id, j.revision_id
            from knowledge_index_jobs j
            join course_knowledge_articles a on a.id = j.article_id
            join course_knowledge_revisions r on r.id = j.revision_id and r.revision = a.current_revision
            where j.status = ? order by j.created_at limit 100
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            List<IndexJob> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new IndexJob(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_JOB_READ_FAILED", "Failed to read knowledge index jobs", error);
        }
    }

    private List<KnowledgeChunkRecord> loadChunks(String revisionId) {
        String sql = """
            select c.id, c.document_id, c.article_id, c.revision_id, r.revision, c.chunk_index,
                r.title, a.course_title, a.section_title, c.heading_path, c.content, a.visibility, a.owner_id,
                coalesce((select group_concat(l.knowledge_point, char(31)) from course_knowledge_point_links l
                    where l.revision_id = r.id), '') knowledge_points
            from knowledge_chunks_v2 c
            join course_knowledge_revisions r on r.id = c.revision_id
            join course_knowledge_articles a on a.id = c.article_id and a.current_revision = r.revision
            where c.revision_id = ? order by c.chunk_index
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, revisionId);
            List<KnowledgeChunkRecord> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String points = rows.getString("knowledge_points");
                    result.add(new KnowledgeChunkRecord(rows.getString("id"), rows.getString("document_id"),
                        rows.getString("article_id"), rows.getString("revision_id"), rows.getInt("revision"),
                        rows.getInt("chunk_index"), rows.getString("title"), rows.getString("course_title"),
                        rows.getString("section_title"), points.isBlank() ? List.of() : List.of(points.split("\\u001f")),
                        rows.getString("heading_path"), rows.getString("content"),
                        KnowledgeVisibility.valueOf(rows.getString("visibility")), rows.getString("owner_id")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_CHUNK_READ_FAILED", "Failed to read knowledge chunks", error);
        }
    }

    private void complete(IndexJob job, List<KnowledgeChunkRecord> chunks, EmbeddingProvider.EmbeddingBatch batch) {
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement updateChunks = connection.prepareStatement(
                     "update knowledge_chunks_v2 set index_status = 'INDEXED' where revision_id = ?");
                 PreparedStatement updateJob = connection.prepareStatement("""
                     update knowledge_index_jobs set status = 'COMPLETED', attempt_count = attempt_count + 1,
                         error_message = null, updated_at = ? where id = ?
                     """);
                 PreparedStatement profile = connection.prepareStatement("""
                     insert into knowledge_embedding_profiles(profile_id, provider, model, dimensions, content_fingerprint, updated_at)
                     values ('active', ?, ?, ?, ?, ?)
                     on conflict(profile_id) do update set provider=excluded.provider, model=excluded.model,
                         dimensions=excluded.dimensions, content_fingerprint=excluded.content_fingerprint, updated_at=excluded.updated_at
                     """)) {
                String now = Instant.now().toString();
                updateChunks.setString(1, job.revisionId()); updateChunks.executeUpdate();
                updateJob.setString(1, now); updateJob.setString(2, job.id()); updateJob.executeUpdate();
                profile.setString(1, batch.provider()); profile.setString(2, batch.model());
                profile.setInt(3, batch.dimensions()); profile.setString(4, chunks.getFirst().id() + ":" + chunks.size());
                profile.setString(5, now); profile.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_COMPLETE_FAILED", "Failed to complete knowledge index job", error);
        }
    }

    private void fail(IndexJob job, String message) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement chunks = connection.prepareStatement(
                 "update knowledge_chunks_v2 set index_status = 'FAILED' where revision_id = ?");
             PreparedStatement update = connection.prepareStatement("""
                 update knowledge_index_jobs set status = 'FAILED', attempt_count = attempt_count + 1,
                     error_message = ?, updated_at = ? where id = ?
                 """)) {
            chunks.setString(1, job.revisionId()); chunks.executeUpdate();
            update.setString(1, message); update.setString(2, Instant.now().toString()); update.setString(3, job.id()); update.executeUpdate();
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_FAIL_STATE_FAILED", "Failed to store knowledge index failure", error);
        }
    }

    private void markJob(String id, String status, String message) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(
                 "update knowledge_index_jobs set status = ?, error_message = ?, updated_at = ? where id = ?")) {
            statement.setString(1, status); statement.setString(2, message);
            statement.setString(3, Instant.now().toString()); statement.setString(4, id); statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("KNOWLEDGE_INDEX_JOB_UPDATE_FAILED", "Failed to update knowledge index job", error);
        }
    }

    private static SqlTeacherException failure(String code, String message, Throwable cause) {
        return new SqlTeacherException(code, message, cause);
    }
    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value.substring(0, Math.min(500, value.length()));
    }
    private record IndexJob(String id, String articleId, String revisionId) {}
}
