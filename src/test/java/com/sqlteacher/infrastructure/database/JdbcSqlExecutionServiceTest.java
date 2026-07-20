package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.mock.MockLearningEventService;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSqlExecutionServiceTest {
    @Test
    void shouldMapQueryRowsAndReportTruncation(@TempDir Path tempDirectory) throws Exception {
        JdbcConnectionFactory connectionFactory = createInitializedFactory(tempDirectory);
        JdbcSqlExecutionService service = createService(connectionFactory);

        SqlExecutionResult result = service.execute(new SqlExecutionRequest(
            "demo",
            "SELECT id, name FROM student ORDER BY id",
            2,
            Duration.ofSeconds(5)
        ));

        assertTrue(result.success());
        assertEquals(java.util.List.of("id", "name"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals("Alice", result.rows().getFirst().get("name"));
        assertEquals("Bob", result.rows().get(1).get("name"));
        assertTrue(result.truncated());
        assertEquals(0, result.affectedRows());
    }

    @Test
    void shouldRequireConfirmationBeforeUpdatingData(@TempDir Path tempDirectory) throws Exception {
        JdbcConnectionFactory connectionFactory = createInitializedFactory(tempDirectory);
        JdbcSqlExecutionService service = createService(connectionFactory);
        String sql = "UPDATE student SET score = 100 WHERE id = 1";

        SqlTeacherException exception = assertThrows(
            SqlTeacherException.class,
            () -> service.execute(new SqlExecutionRequest(
                "demo", sql, 100, Duration.ofSeconds(5)
            ))
        );
        assertEquals("SQL_CONFIRMATION_REQUIRED", exception.errorCode());

        SqlExecutionResult result = service.execute(new SqlExecutionRequest(
            "demo", sql, 100, Duration.ofSeconds(5), true
        ));
        assertTrue(result.success());
        assertEquals(1, result.affectedRows());
    }

    @Test
    void shouldNotExposeJdbcDetailsWhenExecutionFails() {
        JdbcSqlExecutionService service = new JdbcSqlExecutionService(
            (connectionId, timeout) -> {
                throw new SQLException("password=do-not-display", "28000", 1045);
            },
            new SqlResultMapper(),
            new DefaultSqlRiskAnalysisService(),
            new MockLearningEventService()
        );

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> service.execute(new SqlExecutionRequest(
                "course.mysql",
                "SELECT 1",
                10,
                Duration.ofSeconds(1)
            ))
        );

        assertEquals("DATABASE_AUTHENTICATION_FAILED", error.errorCode());
        assertEquals("数据库身份验证失败，请检查用户名和临时密码。", error.getMessage());
    }

    @Test
    void shouldApplyMysqlDialectRulesBeforeOpeningAConnection() {
        AtomicBoolean connectionOpened = new AtomicBoolean();
        JdbcConnectionProvider provider = new JdbcConnectionProvider() {
            @Override
            public Connection open(String connectionId, Duration timeout) {
                connectionOpened.set(true);
                throw new AssertionError("Blocked SQL must not open a JDBC connection");
            }

            @Override
            public DatabaseDialect dialect(String connectionId) {
                return DatabaseDialect.MYSQL;
            }
        };
        JdbcSqlExecutionService service = new JdbcSqlExecutionService(
            provider,
            new SqlResultMapper(),
            new DefaultSqlRiskAnalysisService(),
            new MockLearningEventService()
        );

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> service.execute(new SqlExecutionRequest(
                "course.mysql",
                "SELECT * FROM student INTO OUTFILE '/tmp/student.csv'",
                10,
                Duration.ofSeconds(1)
            ))
        );

        assertEquals("SQL_BLOCKED", error.errorCode());
        assertFalse(connectionOpened.get());
    }

    private static JdbcSqlExecutionService createService(JdbcConnectionFactory connectionFactory) {
        LearningEventRecorder recorder = new JdbcLearningEventRecorder(connectionFactory);
        DefaultLearningEventService eventService = new DefaultLearningEventService(recorder);
        
        return new JdbcSqlExecutionService(
            (connectionId, timeout) -> connectionFactory.open(connectionId),
            new SqlResultMapper(),
            new DefaultSqlRiskAnalysisService(),
            eventService
        );
    }

    private static JdbcConnectionFactory createInitializedFactory(Path tempDirectory) throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(configuration);

        // Initialize app database with learning_events table
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDirectory.resolve("app.db"));
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

        try (Connection connection = connectionFactory.open("demo");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE student (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    score INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                INSERT INTO student (id, name, score) VALUES
                    (1, 'Alice', 92),
                    (2, 'Bob', 85),
                    (3, 'Cathy', 78),
                    (4, 'David', 88),
                    (5, 'Eve', 95)
                """);
        }

        return connectionFactory;
    }
}
