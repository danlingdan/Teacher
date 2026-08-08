package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.activity.DefaultActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.QuizActivityEvaluator;
import com.sqlteacher.application.activity.TraceActivityEvaluator;
import com.sqlteacher.application.activity.CodeActivityEvaluator;
import com.sqlteacher.application.activity.ActivityResourceUsage;
import com.sqlteacher.application.runner.CodeRunResult;
import com.sqlteacher.application.runner.CodeRunner;
import com.sqlteacher.application.runner.RunnerCapability;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivitySpecification;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeActivitySpecification;
import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcActivityLearningServiceTest {
    @TempDir Path tempDir;

    @Test
    void shouldLoadAndPersistTheBuiltInBinaryTreeLoop() throws Exception {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        new SqliteSchemaMigrator().migrate(databases.appDatabasePath());
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        var owner = (com.sqlteacher.application.event.LearningEventOwnerProvider) () -> "student-1";
        var events = new DefaultLearningEventService(new JdbcLearningEventRecorder(connections), owner);
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(
            new QuizActivityEvaluator(), new TraceActivityEvaluator()
        ));
        var service = new JdbcActivityLearningService(connections, owner, events, dispatcher,
            Clock.fixed(Instant.parse("2026-08-09T01:00:00Z"), ZoneOffset.UTC));

        var quiz = service.loadDefinition("tree-traversal-quiz");
        var trace = service.loadDefinition("tree-preorder-trace");
        var quizSubmission = service.submit(quiz.id(),
            new QuizActivityArtifact(Map.of("order-rule", "root-left-right")));
        var traceSubmission = service.submit(trace.id(),
            new TraceActivityArtifact(((TraceActivitySpecification) trace.specification()).expectedNodeIds()));

        assertEquals(ActivityType.QUIZ, quiz.type());
        assertEquals(ActivityType.TRACE, trace.type());
        assertTrue(quizSubmission.evaluation().passed());
        assertTrue(traceSubmission.evaluation().passed());
        assertNotNull(traceSubmission.evaluationId());
        var teacher = new DesktopAccessProfile(DesktopAccessProfile.Kind.TEACHER, "teacher-1", "Teacher",
            "teacher@example.test", java.util.Set.of(UserRole.TEACHER), java.util.Set.of());
        var reviews = new JdbcActivityReviewService(connections,
            Clock.fixed(Instant.parse("2026-08-09T01:05:00Z"), ZoneOffset.UTC));
        var review = reviews.latest(teacher, trace.id()).orElseThrow();
        reviews.publish(teacher, review.evaluationId(), "先确认根节点，再按左、右子树递归展开。");
        assertEquals("先确认根节点，再按左、右子树递归展开。",
            service.latestFeedback(trace.id()).orElseThrow().comment());
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            assertEquals(2, scalar(statement, "select count(*) from activity_session where owner_id='student-1'"));
            assertEquals(2, scalar(statement, "select count(*) from activity_evaluation_result where owner_id='student-1'"));
            assertEquals(2, scalar(statement, "select count(*) from learning_events where activity_type in ('QUIZ','TRACE')"));
            assertEquals(1, scalar(statement, "select count(*) from activity_feedback where status='PUBLISHED'"));
        }
    }

    @Test
    void shouldLoadAndPersistCodeActivityEvidenceWithoutTrustingLocalMode() throws Exception {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("code-app.db"),
            tempDir.resolve("code-demo.db"));
        new SqliteSchemaMigrator().migrate(databases.appDatabasePath());
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        var owner = (com.sqlteacher.application.event.LearningEventOwnerProvider) () -> "student-code";
        var events = new DefaultLearningEventService(new JdbcLearningEventRecorder(connections), owner);
        CodeRunner runner = new CodeRunner() {
            @Override public List<RunnerCapability> capabilities() {
                return List.of(new RunnerCapability(CodeLanguage.PYTHON, true, ""));
            }
            @Override public CodeRunResult run(com.sqlteacher.application.runner.CodeRunRequest request,
                                               com.sqlteacher.application.runner.RunnerCancellation cancellation) {
                String output = request.standardInput().startsWith("2") ? "5\n" : "-3\n";
                return new CodeRunResult(RunnerFailureReason.NONE, 0, output, "",
                    new ActivityResourceUsage(Duration.ofMillis(2), output.length(), 0));
            }
        };
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(new CodeActivityEvaluator(runner)));
        var service = new JdbcActivityLearningService(connections, owner, events, dispatcher,
            Clock.fixed(Instant.parse("2026-08-09T02:00:00Z"), ZoneOffset.UTC));

        var definition = service.loadDefinition("code-sum-python");
        var specification = (CodeActivitySpecification) definition.specification();
        var submission = service.submit(definition.id(),
            new CodeActivityArtifact(CodeLanguage.PYTHON, specification.starterCode()));

        assertEquals(ActivityType.CODE, definition.type());
        assertTrue(submission.evaluation().passed());
        assertEquals("CODE_PASSED", submission.evaluation().reasonCode());
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, "select count(*) from activity_evaluation_result "
                + "where owner_id='student-code' and activity_type='CODE'"));
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
