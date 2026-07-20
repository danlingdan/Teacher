package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.metadata.DatabaseTable;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentProfileDatabaseFlowTest {
    @Test
    void shouldUseSelectedProfileForMetadataAndSqlExecution(@TempDir Path tempDir) throws Exception {
        DatabaseConfiguration databaseConfiguration = new DatabaseConfiguration(
            tempDir.resolve("app.db"),
            tempDir.resolve("demo.db")
        );
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            databaseConfiguration,
            new AiConfiguration(
                URI.create("http://localhost:11434"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                "test-model"
            )
        );
        new SqliteAppDatabaseInitializer(configuration).initialize();
        Path courseDatabase = tempDir.resolve("course.db");
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + courseDatabase);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table lesson(id integer primary key, title text not null)");
            statement.executeUpdate("insert into lesson(id, title) values (1, 'Profile routing')");
        }

        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(databaseConfiguration);
        JdbcConnectionManagementService managementService = new JdbcConnectionManagementService(
            connectionFactory,
            databaseConfiguration
        );
        managementService.saveProfile(new DatabaseConnectionProfile(
            "course.sqlite",
            "Course SQLite",
            new SqliteConnectionTarget(courseDatabase),
            true,
            true,
            false
        ));
        String currentId = managementService.selectProfile("course.sqlite").id();
        JdbcConnectionProvider provider = new ProfileAwareJdbcConnectionProvider(
            connectionFactory,
            managementService,
            new InMemoryDatabaseCredentialSession()
        );
        JdbcDatabaseMetadataService metadataService = new JdbcDatabaseMetadataService(provider);
        JdbcSqlExecutionService executionService = new JdbcSqlExecutionService(
            provider,
            new SqlResultMapper(),
            new DefaultSqlRiskAnalysisService(),
            new DefaultLearningEventService(new JdbcLearningEventRecorder(connectionFactory))
        );

        List<DatabaseTable> tables = metadataService.listTables(currentId);
        SqlExecutionResult result = executionService.execute(new SqlExecutionRequest(
            currentId,
            "select title from lesson",
            10,
            Duration.ofSeconds(2)
        ));

        assertTrue(tables.stream().anyMatch(table -> table.name().equals("lesson")));
        assertEquals("Profile routing", result.rows().getFirst().get("title"));

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class,
            () -> executionService.execute(new SqlExecutionRequest(
                currentId,
                "update lesson set title = 'Blocked' where id = 1",
                10,
                Duration.ofSeconds(2),
                true
            ))
        );
        assertEquals("SQL_READ_ONLY_CONNECTION", error.errorCode());
    }
}
