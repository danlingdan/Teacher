package com.sqlteacher.desktop.integration;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.DatabaseServiceConfig;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstIntegrationFlowTest {

    @Test
    void shouldRunFirstDesktopToSqliteIntegrationScenarios(@TempDir Path tempDirectory) {
        SqlTeacherConfiguration configuration = configuration(tempDirectory);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SqlTeacherConfiguration.class, () -> configuration);
            context.registerBean(
                DatabaseInitializationService.class,
                () -> new SqliteAppDatabaseInitializer(configuration)
            );
            context.register(DatabaseServiceConfig.class);
            context.refresh();

            context.getBean(DatabaseInitializationService.class).initialize();
            SqlExecutionService service = context.getBean(SqlExecutionService.class);

            SqlExecutionResult rows = execute(service, "SELECT * FROM student ORDER BY id", 100);
            assertTrue(rows.success());
            assertEquals(2, rows.rows().size());
            assertEquals("Alice", rows.rows().getFirst().get("name"));

            SqlExecutionResult empty = execute(service, "SELECT * FROM student WHERE score > 999", 100);
            assertTrue(empty.success());
            assertTrue(empty.rows().isEmpty());

            assertErrorCode(service, "SELECT FROM student", "SQL_EXECUTION_FAILED");
            assertErrorCode(service, "SELECT * FROM student; SELECT 1", "SQL_BLOCKED");
            assertErrorCode(service, "DROP TABLE student", "SQL_BLOCKED");
            assertErrorCode(
                service,
                "UPDATE student SET score = 0 WHERE id = 1",
                "SQL_CONFIRMATION_REQUIRED"
            );

            SqlExecutionResult unchanged = execute(
                service,
                "SELECT score FROM student WHERE id = 1",
                100
            );
            assertEquals(92, unchanged.rows().getFirst().get("score"));

            SqlExecutionResult truncated = execute(service, "SELECT * FROM student ORDER BY id", 1);
            assertEquals(1, truncated.rows().size());
            assertTrue(truncated.truncated());
        }
    }

    private static SqlExecutionResult execute(SqlExecutionService service, String sql, int maxRows) {
        return service.execute(new SqlExecutionRequest(
            "demo",
            sql,
            maxRows,
            Duration.ofSeconds(5)
        ));
    }

    private static void assertErrorCode(SqlExecutionService service, String sql, String expectedCode) {
        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> execute(service, sql, 100)
        );
        assertEquals(expectedCode, error.errorCode());
        assertFalse(error.getMessage().isBlank());
    }

    private static SqlTeacherConfiguration configuration(Path tempDirectory) {
        return new SqlTeacherConfiguration(
            "SQLTeacher integration test",
            tempDirectory,
            new DatabaseConfiguration(
                tempDirectory.resolve("app.db"),
                tempDirectory.resolve("demo.db")
            ),
            new AiConfiguration(
                URI.create("http://localhost:11434"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "test-model"
            )
        );
    }
}
