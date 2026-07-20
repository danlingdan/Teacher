package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.event.LearningEvent;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventType;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventQueryService.EventStatistics;
import com.sqlteacher.application.event.LearningEventQueryService.QueriedLearningEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLearningEventQueryServiceTest {

    @Test
    void shouldQueryEventsByType(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));

        LearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);

        Instant now = Instant.now();

        // Record events of different types
        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            true,
            Map.of("statementType", "SELECT")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_RISK_BLOCKED,
            now,
            "demo",
            false,
            Map.of("statementType", "DROP", "riskLevel", "HIGH")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            true,
            Map.of("statementType", "INSERT")
        ));

        // Query by type
        List<QueriedLearningEvent> executionEvents = queryService.queryEventsByType(
            LearningEventType.SQL_EXECUTION, null, null);

        assertEquals(2, executionEvents.size());
        assertEquals(LearningEventType.SQL_EXECUTION, executionEvents.get(0).type());

        List<QueriedLearningEvent> blockedEvents = queryService.queryEventsByType(
            LearningEventType.SQL_RISK_BLOCKED, null, null);

        assertEquals(1, blockedEvents.size());
        assertEquals(LearningEventType.SQL_RISK_BLOCKED, blockedEvents.get(0).type());
    }

    @Test
    void shouldQueryEventsByConnection(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));

        LearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);

        Instant now = Instant.now();

        // Record events for different connections
        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            true,
            Map.of("statementType", "SELECT")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "other",
            true,
            Map.of("statementType", "SELECT")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_RISK_BLOCKED,
            now,
            "demo",
            false,
            Map.of("statementType", "DROP")
        ));

        // Query by connection
        List<QueriedLearningEvent> demoEvents = queryService.queryEventsByConnection("demo", null, null);

        assertEquals(2, demoEvents.size());
        assertEquals("demo", demoEvents.get(0).connectionId());

        List<QueriedLearningEvent> otherEvents = queryService.queryEventsByConnection("other", null, null);

        assertEquals(1, otherEvents.size());
        assertEquals("other", otherEvents.get(0).connectionId());
    }

    @Test
    void shouldQueryEventsWithTimeRange(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));

        LearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);

        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant oneHourLater = now.plus(1, ChronoUnit.HOURS);

        // Record events at different times
        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            oneHourAgo,
            "demo",
            true,
            Map.of("statementType", "SELECT")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            true,
            Map.of("statementType", "INSERT")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            oneHourLater,
            "demo",
            true,
            Map.of("statementType", "UPDATE")
        ));

        // Query with time range
        List<QueriedLearningEvent> eventsInRange = queryService.queryEventsByType(
            LearningEventType.SQL_EXECUTION, oneHourAgo.minusSeconds(1), now.plusSeconds(1));

        assertEquals(2, eventsInRange.size());

        // Query with only start time
        List<QueriedLearningEvent> eventsFromStart = queryService.queryEventsByType(
            LearningEventType.SQL_EXECUTION, now.minusSeconds(1), null);

        assertEquals(2, eventsFromStart.size());

        // Query with only end time
        List<QueriedLearningEvent> eventsUntilEnd = queryService.queryEventsByType(
            LearningEventType.SQL_EXECUTION, null, now.plusSeconds(1));

        assertEquals(2, eventsUntilEnd.size());
    }

    @Test
    void shouldGetEventStatistics(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));

        LearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);

        Instant now = Instant.now();

        // Record various events
        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            true,
            Map.of("statementType", "SELECT")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            false,
            Map.of("statementType", "SELECT", "errorCode", "ERROR")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.SQL_RISK_BLOCKED,
            now,
            "demo",
            false,
            Map.of("statementType", "DROP")
        ));

        recorder.record(new LearningEvent(
            LearningEventType.AI_SQL_GENERATED,
            now,
            "other",
            true,
            Map.of("model", "llama3.2")
        ));

        // Get statistics
        EventStatistics stats = queryService.getEventStatistics(null, null);

        assertEquals(4, stats.totalEvents());
        assertEquals(2, stats.successfulEvents());
        assertEquals(2, stats.failedEvents());
        assertTrue(stats.eventsByType().containsKey(LearningEventType.SQL_EXECUTION));
        assertTrue(stats.eventsByType().containsKey(LearningEventType.SQL_RISK_BLOCKED));
        assertTrue(stats.eventsByType().containsKey(LearningEventType.AI_SQL_GENERATED));
        assertTrue(stats.eventsByConnection().containsKey("demo"));
        assertTrue(stats.eventsByConnection().containsKey("other"));
    }

    @Test
    void shouldParseAttributesCorrectly(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));

        LearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);

        Instant now = Instant.now();

        // Record event with special characters in attributes
        recorder.record(new LearningEvent(
            LearningEventType.SQL_EXECUTION,
            now,
            "demo",
            true,
            Map.of(
                    "message", "test=with=special,including comma",
                    "code", "error=code",
                    "path", "C:\\path\\to\\file")
        ));

        // Query and verify attributes are parsed correctly
        List<QueriedLearningEvent> events = queryService.queryEventsByType(
            LearningEventType.SQL_EXECUTION, null, null);

        assertEquals(1, events.size());
        Map<String, String> attributes = events.get(0).attributes();
        assertEquals("test=with=special,including comma", attributes.get("message"));
        assertEquals("error=code", attributes.get("code"));
        assertEquals("C:\\path\\to\\file", attributes.get("path"));
        assertThrows(UnsupportedOperationException.class, () -> attributes.put("new", "value"));
    }

    @Test
    void shouldRejectInvertedTimeRange(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
                tempDirectory.resolve("app.db"),
                tempDirectory.resolve("demo.db")
        );
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));
        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);
        Instant start = Instant.parse("2026-07-20T02:00:00Z");
        Instant end = Instant.parse("2026-07-20T01:00:00Z");

        assertThrows(IllegalArgumentException.class, () ->
                queryService.queryEventsByType(LearningEventType.SQL_EXECUTION, start, end));
        assertThrows(IllegalArgumentException.class, () ->
                queryService.queryEventsByConnection("demo", start, end));
        assertThrows(IllegalArgumentException.class, () ->
                queryService.getEventStatistics(start, end));
    }

    @Test
    void shouldReturnEmptyListWhenNoEvents(@TempDir Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);
        initializeAppDatabase(tempDirectory.resolve("app.db"));

        LearningEventQueryService queryService = new JdbcLearningEventQueryService(connectionFactory);

        // Query when no events exist
        List<QueriedLearningEvent> events = queryService.queryEventsByType(
            LearningEventType.SQL_EXECUTION, null, null);

        assertNotNull(events);
        assertTrue(events.isEmpty());

        EventStatistics stats = queryService.getEventStatistics(null, null);
        assertEquals(0, stats.totalEvents());
        assertEquals(0, stats.successfulEvents());
        assertEquals(0, stats.failedEvents());
        assertTrue(stats.eventsByType().isEmpty());
        assertTrue(stats.eventsByConnection().isEmpty());
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
