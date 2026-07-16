package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.event.LearningEvent;
import com.sqlteacher.application.event.LearningEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLearningEventRecorderTest {
    
    @Test
    void shouldPersistEventToDatabase(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );
        
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));
        
        JdbcLearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        
        LearningEvent event = new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            Instant.now(),
            "demo",
            true,
            Map.of("statementType", "SELECT", "durationMs", "100", "resultCount", "5")
        );
        
        recorder.record(event);
        
        // Verify event was persisted
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM learning_events")) {
            
            assertTrue(resultSet.next());
            assertEquals("SQL_EXECUTION", resultSet.getString("event_type"));
            assertEquals("demo", resultSet.getString("connection_id"));
            assertEquals(true, resultSet.getBoolean("successful"));
            assertNotNull(resultSet.getString("attributes"));
        }
    }
    
    @Test
    void shouldPersistEventWithNullAttributes(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );
        
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));
        
        JdbcLearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        
        LearningEvent event = new LearningEvent(
            LearningEventType.SQL_RISK_BLOCKED,
            Instant.now(),
            "demo",
            false,
            Map.of()
        );
        
        recorder.record(event);
        
        // Verify event was persisted
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM learning_events")) {
            
            assertTrue(resultSet.next());
            assertEquals("SQL_RISK_BLOCKED", resultSet.getString("event_type"));
            assertEquals("demo", resultSet.getString("connection_id"));
            assertEquals(false, resultSet.getBoolean("successful"));
        }
    }
    
    @Test
    void shouldPersistMultipleEvents(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );
        
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));
        
        JdbcLearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        
        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            Instant.now(),
            "demo",
            true,
            Map.of("statementType", "SELECT")
        ));
        
        recorder.record(new LearningEvent(
            LearningEventType.SQL_RISK_BLOCKED,
            Instant.now(),
            "demo",
            false,
            Map.of("statementType", "DROP", "riskLevel", "HIGH")
        ));
        
        // Verify both events were persisted
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM learning_events")) {
            
            assertTrue(resultSet.next());
            assertEquals(2, resultSet.getInt(1));
        }
    }
    
    @Test
    void shouldHandleSpecialCharactersInAttributes(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );
        
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));
        
        JdbcLearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        
        LearningEvent event = new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            Instant.now(),
            "demo",
            true,
            Map.of("message", "test=with=special", "code", "error=code")
        );
        
        recorder.record(event);
        
        // Verify event was persisted with escaped attributes
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT attributes FROM learning_events")) {
            
            assertTrue(resultSet.next());
            String attributes = resultSet.getString("attributes");
            assertNotNull(attributes);
            assertTrue(attributes.contains("message") && attributes.contains("code"));
        }
    }
    
    private static void initializeAppDatabase(Path databasePath) throws Exception {
        SqliteDriver.ensureLoaded();
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                create table if not exists learning_events (
                    id integer primary key autoincrement,
                    event_type text not null,
                    occurred_at text not null,
                    connection_id text not null,
                    successful integer not null,
                    attributes text,
                    created_at text not null default current_timestamp
                )
                """);
        }
    }
}