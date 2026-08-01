package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public final class SqliteKnowledgeReadStateService implements KnowledgeReadStateService {
    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;

    public SqliteKnowledgeReadStateService(JdbcConnectionFactory connectionFactory, LearningEventOwnerProvider ownerProvider) {
        this.connectionFactory = connectionFactory;
        this.ownerProvider = ownerProvider;
    }

    @Override
    public ReadState save(String articleId, int revision, int progressPercent) {
        ReadState state = new ReadState(articleId, revision, progressPercent, Instant.now());
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 insert into knowledge_read_state(owner_id, article_id, revision, progress_percent, last_read_at)
                 values (?, ?, ?, ?, ?)
                 on conflict(owner_id, article_id) do update set revision=excluded.revision,
                     progress_percent=excluded.progress_percent, last_read_at=excluded.last_read_at
                 """)) {
            statement.setString(1, ownerProvider.currentOwnerId()); statement.setString(2, articleId);
            statement.setInt(3, revision); statement.setInt(4, progressPercent); statement.setString(5, state.lastReadAt().toString());
            statement.executeUpdate();
            return state;
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_READ_STATE_WRITE_FAILED", "Failed to save knowledge reading progress", error);
        }
    }

    @Override
    public Optional<ReadState> find(String articleId) {
        if (articleId == null || articleId.isBlank()) throw new IllegalArgumentException("articleId must not be blank");
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 select revision, progress_percent, last_read_at from knowledge_read_state where owner_id = ? and article_id = ?
                 """)) {
            statement.setString(1, ownerProvider.currentOwnerId()); statement.setString(2, articleId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new ReadState(articleId, rows.getInt(1), rows.getInt(2), Instant.parse(rows.getString(3))))
                    : Optional.empty();
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("KNOWLEDGE_READ_STATE_READ_FAILED", "Failed to read knowledge reading progress", error);
        }
    }
}
