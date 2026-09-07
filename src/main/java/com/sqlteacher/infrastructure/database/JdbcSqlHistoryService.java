package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.execution.SqlHistoryEntry;
import com.sqlteacher.application.execution.SqlHistoryService;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcSqlHistoryService implements SqlHistoryService {
    private static final Logger log = LoggerFactory.getLogger(JdbcSqlHistoryService.class);

    private final JdbcConnectionFactory connectionFactory;

    public JdbcSqlHistoryService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }

    @Override
    public void record(SqlHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                insert into sql_history(connection_id, sql_text, successful, row_count, duration_millis, created_at)
                values (?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, entry.connectionId());
                statement.setString(2, entry.sqlText());
                statement.setInt(3, entry.successful() ? 1 : 0);
                statement.setInt(4, Math.max(0, entry.rowCount()));
                statement.setLong(5, Math.max(0, entry.durationMillis()));
                // created_at 是 text 列：统一存 ISO-8601，避免 Timestamp 往返解析失败。
                statement.setString(6, entry.createdAt().toString());
                statement.executeUpdate();
            }
            trimOldEntries(connection);
            connection.commit();
        } catch (SQLException error) {
            // 历史是非关键旁路：记录失败不应让已成功执行的查询向用户报错。
            log.warn("SQL history recording failed, connectionId={}, failure={}", entry.connectionId(), error.getMessage());
        }
    }

    private static void trimOldEntries(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            delete from sql_history
            where id not in (select id from sql_history order by created_at desc, id desc limit ?)
            """)) {
            statement.setInt(1, MAX_ENTRIES);
            statement.executeUpdate();
        }
    }

    @Override
    public List<SqlHistoryEntry> list(int limit) {
        int boundedLimit = Math.clamp(limit, 1, MAX_ENTRIES);
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 select h.connection_id, coalesce(p.display_name, '') as connection_name,
                        h.sql_text, h.successful, h.row_count, h.duration_millis, h.created_at
                 from sql_history h
                 left join connection_profiles p on p.id = h.connection_id
                 order by h.created_at desc, h.id desc
                 limit ?
                 """)) {
            statement.setInt(1, boundedLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SqlHistoryEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(new SqlHistoryEntry(
                        resultSet.getString("connection_id"),
                        resultSet.getString("connection_name"),
                        resultSet.getString("sql_text"),
                        resultSet.getInt("successful") == 1,
                        resultSet.getInt("row_count"),
                        resultSet.getLong("duration_millis"),
                        parseInstant(resultSet.getString("created_at"))
                    ));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException error) {
            log.error("SQL history read failed, failure={}", error.getMessage());
            throw new SqlTeacherException("SQL_HISTORY_READ_FAILED", "无法读取 SQL 执行历史。");
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException error) {
            return Instant.EPOCH;
        }
    }

    @Override
    public void clear() {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("delete from sql_history")) {
            statement.executeUpdate();
        } catch (SQLException error) {
            log.error("SQL history clear failed, failure={}", error.getMessage());
            throw new SqlTeacherException("SQL_HISTORY_CLEAR_FAILED", "无法清空 SQL 执行历史。");
        }
    }
}
