package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEvent;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
                attributes
            ) VALUES (?, ?, ?, ?, ?)
            """;
        
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            
            statement.setString(1, event.type().name());
            statement.setString(2, Timestamp.from(event.occurredAt()).toString());
            statement.setString(3, event.connectionId());
            statement.setBoolean(4, event.successful());
            statement.setString(5, LearningEventAttributesCodec.serialize(event.attributes()));
            
            statement.executeUpdate();
        }
    }
    
}
