package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEvent;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class JdbcLearningEventRecorder implements LearningEventRecorder {
    private static final Logger log = LoggerFactory.getLogger(JdbcLearningEventRecorder.class);
    private static final ReentrantLock lock = new ReentrantLock();
    
    private final JdbcConnectionFactory connectionFactory;
    
    public JdbcLearningEventRecorder(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }
    
    @Override
    public void record(LearningEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        
        // Fire-and-forget pattern: don't fail if event recording fails
        try {
            lock.lock();
            try {
                persistEvent(event);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            log.error("Failed to persist learning event: type={}, connectionId={}", 
                    event.type(), event.connectionId(), e);
        }
    }
    
    private void persistEvent(LearningEvent event) throws SQLException {
        String sql = """
            INSERT INTO learning_events (
                event_type, 
                occurred_at, 
                connection_id, 
                successful, 
                attributes,
                activity_id,
                activity_type,
                evaluator_version,
                evidence_version,
                reason_code
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, event.type().name());
            // 统一 ISO-8601：读取端（学习诊断）用 Instant 解析，不能写 Timestamp 本地格式。
            statement.setString(2, event.occurredAt().toString());
            statement.setString(3, event.connectionId());
            statement.setBoolean(4, event.successful());
            statement.setString(5, LearningEventAttributesCodec.serialize(event.attributes()));
            statement.setString(6, event.attributes().get("activityId"));
            statement.setString(7, event.attributes().get("activityType"));
            statement.setString(8, event.attributes().get("evaluatorVersion"));
            statement.setString(9, event.attributes().get("evidenceVersion"));
            statement.setString(10, event.attributes().get("reasonCode"));
            
            statement.executeUpdate();
        }
    }
    
}
