package com.sqlteacher.desktop.integration;

import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.ai.Nl2SqlServiceImpl;
import com.sqlteacher.infrastructure.database.DatabaseServiceConfig;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondIntegrationFlowTest {

    @Test
    void shouldIntegrateMetadataRiskExecutionAiDraftAndEventRecording(@TempDir Path tempDirectory)
        throws Exception {
        SqlTeacherConfiguration configuration = configuration(tempDirectory);

        try (AnnotationConfigApplicationContext context = databaseContext(configuration)) {
            context.getBean(DatabaseInitializationService.class).initialize();

            SqlExecutionService executionService = context.getBean(SqlExecutionService.class);
            DatabaseMetadataService metadataService = context.getBean(DatabaseMetadataService.class);
            SqlRiskAnalysisService riskService = context.getBean(SqlRiskAnalysisService.class);
            LearningEventService eventService = context.getBean(LearningEventService.class);

            assertTrue(metadataService.listTables("demo").stream()
                .anyMatch(table -> table.name().equals("student")));

            SqlTeacherException blocked = assertThrows(
                SqlTeacherException.class,
                () -> execute(executionService, "SELECT * FROM student; DELETE FROM student", false)
            );
            assertEquals("SQL_BLOCKED", blocked.errorCode());

            SqlExecutionResult update = execute(
                executionService,
                "UPDATE student SET score = 93 WHERE id = 1",
                true
            );
            assertEquals(1, update.affectedRows());

            AiModelProvider fixedProvider = request -> AiCompletionResult.success(
                "{\"sqlDraft\":\"SELECT name, score FROM student ORDER BY id\","
                    + "\"intent\":\"QUERY\",\"explanation\":\"查询学生成绩\"}",
                request.model()
            );
            Nl2SqlServiceImpl nl2SqlService = new Nl2SqlServiceImpl(
                fixedProvider,
                configuration.ai(),
                metadataService,
                eventService,
                riskService
            );

            Nl2SqlPlan plan = nl2SqlService.generate(new Nl2SqlRequest("查询学生成绩", "demo"));
            assertEquals("SELECT name, score FROM student ORDER BY id", plan.sqlDraft());
            assertEquals("QUERY", plan.intent());

            SqlExecutionResult rows = execute(
                executionService,
                "SELECT score FROM student WHERE id = 1",
                false
            );
            assertEquals(93, rows.rows().getFirst().get("score"));

            Map<String, Long> eventCounts = eventCounts(configuration.database().appDatabasePath());
            assertTrue(eventCounts.getOrDefault("SQL_RISK_BLOCKED", 0L) >= 1);
            assertTrue(eventCounts.getOrDefault("SQL_EXECUTION", 0L) >= 2);
            assertEquals(1, eventCounts.getOrDefault("AI_SQL_GENERATED", 0L));
            assertFalse(eventCounts.containsKey("AI_GENERATION_FAILED"));
        }
    }

    private static AnnotationConfigApplicationContext databaseContext(
        SqlTeacherConfiguration configuration
    ) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SqlTeacherConfiguration.class, () -> configuration);
        context.registerBean(
            DatabaseInitializationService.class,
            () -> new SqliteAppDatabaseInitializer(configuration)
        );
        context.register(DatabaseServiceConfig.class);
        context.refresh();
        return context;
    }

    private static SqlExecutionResult execute(
        SqlExecutionService service,
        String sql,
        boolean riskConfirmed
    ) {
        return service.execute(new SqlExecutionRequest(
            "demo",
            sql,
            100,
            Duration.ofSeconds(5),
            riskConfirmed
        ));
    }

    private static Map<String, Long> eventCounts(Path appDatabasePath) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + appDatabasePath);
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("""
                 SELECT event_type, COUNT(*) AS event_count
                 FROM learning_events
                 GROUP BY event_type
                 """)) {
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            while (resultSet.next()) {
                counts.put(resultSet.getString("event_type"), resultSet.getLong("event_count"));
            }
            return counts.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
        }
    }

    private static SqlTeacherConfiguration configuration(Path tempDirectory) {
        return new SqlTeacherConfiguration(
            "SQLTeacher second integration test",
            tempDirectory,
            new DatabaseConfiguration(
                tempDirectory.resolve("app.db"),
                tempDirectory.resolve("demo.db")
            ),
            new AiConfiguration(
                URI.create("http://localhost:11434"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "integration-model"
            )
        );
    }
}
