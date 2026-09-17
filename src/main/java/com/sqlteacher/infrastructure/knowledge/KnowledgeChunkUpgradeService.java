package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.SqliteKnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * v3.6.0 KBQ-1: 启动后检查分块算法版本（knowledge_index_meta.chunker_version）。算法演进
 * （如结构感知分块）与存量库不一致时，用当前算法重切全部文章并回写版本号，保证关键词
 * 检索的分块质量与算法同步。全程后台执行、失败降级留待下次启动重试。
 */
public final class KnowledgeChunkUpgradeService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkUpgradeService.class);
    private static final long INITIAL_DELAY_SECONDS = 15;
    private static final String CHUNKER_VERSION_KEY = "chunker_version";

    private final SqliteKnowledgeService knowledgeService;
    private final JdbcConnectionFactory connectionFactory;
    private ScheduledExecutorService executor;

    public KnowledgeChunkUpgradeService(SqliteKnowledgeService knowledgeService, JdbcConnectionFactory connectionFactory) {
        this.knowledgeService = knowledgeService;
        this.connectionFactory = connectionFactory;
    }

    /** Starts the daemon scheduler; safe to call once from the wiring. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knowledge-chunk-upgrade");
            thread.setDaemon(true);
            return thread;
        });
        executor.schedule(this::tick, INITIAL_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            if (SqliteKnowledgeService.CHUNKER_VERSION.equals(readMeta(CHUNKER_VERSION_KEY))) {
                return;
            }
            int processed = knowledgeService.rechunkAllArticles();
            writeMeta(CHUNKER_VERSION_KEY, SqliteKnowledgeService.CHUNKER_VERSION);
            log.info("Knowledge chunks upgraded to {} ({} articles re-chunked)",
                SqliteKnowledgeService.CHUNKER_VERSION, processed);
        } catch (RuntimeException error) {
            log.info("Knowledge chunk upgrade deferred: {}", error.getClass().getSimpleName());
        }
    }

    private String readMeta(String key) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(
                 "select value from knowledge_index_meta where key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("value") : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to read knowledge_index_meta", error);
        }
    }

    private void writeMeta(String key, String value) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 insert into knowledge_index_meta(key, value, updated_at) values (?, ?, current_timestamp)
                 on conflict(key) do update set value = excluded.value, updated_at = current_timestamp
                 """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to write knowledge_index_meta", error);
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
