package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.GroundedKnowledgeAnswer;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.planning.TutorFeedbackType;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcGroundedTutorServiceTest {
    @TempDir Path directory;

    @Test
    void shouldStoreOnlyGroundedMetadataAndOwnerScopedEnumFeedback() throws Exception {
        Path app = directory.resolve("app.db");
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration("SQLTeacher", directory,
            new DatabaseConfiguration(app, directory.resolve("demo.db")), new AiConfiguration(
            URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(1), "test"));
        new SqliteAppDatabaseInitializer(configuration).initialize();
        var owner = new InMemoryLearningEventOwnerContext();
        owner.useAuthenticatedUser("student-1");
        var service = new JdbcGroundedTutorService(new StubExplanation(),
            new JdbcConnectionFactory(configuration.database()), owner,
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

        var result = service.ask("SQL", "objective-1", "WHERE 是什么？",
            new CourseKnowledgeSearchFilter("SQL", "", "WHERE", false));
        assertTrue(result.answer().aiGenerated());
        service.feedback(result.sessionId(), TutorFeedbackType.HELPFUL, "");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + app);
             var statement = connection.createStatement();
             var row = statement.executeQuery("select provider,result_code,degraded from grounded_tutor_session")) {
            assertTrue(row.next());
            assertEquals("AI", row.getString(1));
            assertEquals("GROUNDED", row.getString(2));
            assertEquals(0, row.getInt(3));
        }
        owner.useAuthenticatedUser("student-2");
        assertThrows(SecurityException.class,
            () -> service.feedback(result.sessionId(), TutorFeedbackType.CITATION_ERROR, "wrong citation"));
    }

    private static final class StubExplanation implements GroundedKnowledgeExplanationService {
        @Override public AiContextPreview preview(String question, CourseKnowledgeSearchFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override public GroundedKnowledgeAnswer explain(String question, CourseKnowledgeSearchFilter filter) {
            return new GroundedKnowledgeAnswer(true, "WHERE 用于筛选行。", "test-model", List.of(
                new GroundedKnowledgeAnswer.Citation(1, "doc-1", "WHERE", 1, 0, "WHERE 筛选满足条件的行。")),
                "grounded");
        }
    }
}
