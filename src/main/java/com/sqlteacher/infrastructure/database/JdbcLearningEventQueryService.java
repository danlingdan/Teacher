package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventType;
import com.sqlteacher.application.event.LearningEventQueryService.EventStatistics;
import com.sqlteacher.application.event.LearningEventQueryService.QueriedLearningEvent;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

public final class JdbcLearningEventQueryService implements LearningEventQueryService {
    private static final Logger log = LoggerFactory.getLogger(JdbcLearningEventQueryService.class);

    private final JdbcConnectionFactory connectionFactory;

    public JdbcLearningEventQueryService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
    }

    @Override
    public List<QueriedLearningEvent> queryEventsByType(LearningEventType type, Instant start, Instant end) {
        Objects.requireNonNull(type, "type must not be null");
        validateTimeRange(start, end);

        String sql = buildQuerySql("event_type = ?", start, end);
        return executeQuery(sql, statement -> {
            statement.setString(1, type.name());
            setTimestampParameters(statement, 2, start, end);
        });
    }

    @Override
    public List<QueriedLearningEvent> queryEventsByConnection(String connectionId, Instant start, Instant end) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        validateTimeRange(start, end);

        String sql = buildQuerySql("connection_id = ?", start, end);
        return executeQuery(sql, statement -> {
            statement.setString(1, connectionId);
            setTimestampParameters(statement, 2, start, end);
        });
    }

    @Override
    public EventStatistics getEventStatistics(Instant start, Instant end) {
        validateTimeRange(start, end);

        StringBuilder sql = new StringBuilder("""
        SELECT
            successful,
            event_type,
            connection_id
        FROM learning_events
        WHERE 1=1
        """);

        if (start != null) {
            sql.append(" AND occurred_at >= ?");
        }
        if (end != null) {
            sql.append(" AND occurred_at <= ?");
        }

        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {

            setTimestampParameters(statement, 1, start, end);

            long total = 0;
            long success = 0;
            long failed = 0;

            Map<LearningEventType, Long> typeMap = new EnumMap<>(LearningEventType.class);
            Map<String, Long> connectionMap = new HashMap<>();

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {

                    total++;

                    if (rs.getBoolean("successful")) {
                        success++;
                    } else {
                        failed++;
                    }

                    LearningEventType type =
                            LearningEventType.valueOf(rs.getString("event_type"));

                    typeMap.merge(type, 1L, Long::sum);

                    String connectionId = rs.getString("connection_id");

                    connectionMap.merge(connectionId, 1L, Long::sum);
                }
            }

            return new EventStatistics(
                    total,
                    success,
                    failed,
                    typeMap,
                    connectionMap
            );

        } catch (SQLException e) {
            log.error("Failed to query statistics", e);
            throw new SqlTeacherException(
                    "LEARNING_EVENT_STATISTICS_FAILED",
                    "Failed to query event statistics: " + e.getMessage(),
                    e
            );
        }
    }

    private String buildQuerySql(String whereClause, Instant start, Instant end) {
        StringBuilder sql = new StringBuilder("""
        SELECT id, event_type, occurred_at, connection_id, successful, attributes, created_at
        FROM learning_events
        WHERE 1=1
        """);

        sql.append(" AND ").append(whereClause);

        if (start != null) {
            sql.append(" AND occurred_at >= ?");
        }
        if (end != null) {
            sql.append(" AND occurred_at <= ?");
        }

        sql.append(" ORDER BY occurred_at DESC");

        return sql.toString();
    }

    private List<QueriedLearningEvent> executeQuery(String sql, StatementPreparer preparer) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            preparer.prepare(statement);

            List<QueriedLearningEvent> events = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(mapRow(resultSet));
                }
            }

            return List.copyOf(events);
        } catch (SQLException e) {
            log.error("Failed to query learning events", e);
            throw new SqlTeacherException(
                    "LEARNING_EVENT_QUERY_FAILED",
                    "Failed to query learning events: " + e.getMessage(),
                    e
            );
        }
    }

    private void setTimestampParameters(
            PreparedStatement statement,
            int startIndex,
            Instant start,
            Instant end) throws SQLException {

        int index = startIndex;

        if (start != null) {
            statement.setString(index++,
                    Timestamp.from(start).toString());
        }

        if (end != null) {
            statement.setString(index,
                    Timestamp.from(end).toString());
        }
    }

    private QueriedLearningEvent mapRow(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("id");
        LearningEventType type = LearningEventType.valueOf(resultSet.getString("event_type"));
        Instant occurredAt = resultSet.getTimestamp("occurred_at").toInstant();
        String connectionId = resultSet.getString("connection_id");
        boolean successful = resultSet.getBoolean("successful");
        Map<String, String> attributes = LearningEventAttributesCodec.deserialize(resultSet.getString("attributes"));
        Instant createdAt = resultSet.getTimestamp("created_at").toInstant();

        return new QueriedLearningEvent(id, type, occurredAt, connectionId, successful, attributes, createdAt);
    }

    private void validateTimeRange(Instant start, Instant end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("start must not be after end");
        }
    }

    @FunctionalInterface
    private interface StatementPreparer {
        void prepare(PreparedStatement statement) throws SQLException;
    }
}
