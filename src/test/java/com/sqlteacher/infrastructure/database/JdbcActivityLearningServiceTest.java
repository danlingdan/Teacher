package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.activity.DefaultActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.QuizActivityEvaluator;
import com.sqlteacher.application.activity.TraceActivityEvaluator;
import com.sqlteacher.application.activity.SimulationActivityEvaluator;
import com.sqlteacher.application.activity.ProjectActivityEvaluator;
import com.sqlteacher.application.activity.CodeActivityEvaluator;
import com.sqlteacher.application.activity.LabActivityEvaluator;
import com.sqlteacher.application.activity.ReadingActivityEvaluator;
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
import com.sqlteacher.domain.activity.SimulationActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivitySpecification;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivitySpecification;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeActivitySpecification;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.ProjectActivitySpecification;
import com.sqlteacher.domain.activity.CodeLanguage;
import com.sqlteacher.domain.activity.LabActivityArtifact;
import com.sqlteacher.domain.activity.LabActivitySpecification;
import com.sqlteacher.domain.activity.ReadingActivityArtifact;
import com.sqlteacher.domain.activity.ReadingActivitySpecification;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void shouldRunAllBuiltInSimulationCoursesOffline() throws Exception {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("simulation-app.db"),
            tempDir.resolve("simulation-demo.db"));
        new SqliteSchemaMigrator().migrate(databases.appDatabasePath());
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        var owner = (com.sqlteacher.application.event.LearningEventOwnerProvider) () -> "student-simulation";
        var events = new DefaultLearningEventService(new JdbcLearningEventRecorder(connections), owner);
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(new SimulationActivityEvaluator()));
        var service = new JdbcActivityLearningService(connections, owner, events, dispatcher,
            Clock.fixed(Instant.parse("2026-08-09T03:00:00Z"), ZoneOffset.UTC));

        Map<String, String> alpha6FailureReasons = Map.of(
            "se-ci-quality-gate", "SE_ACCEPTANCE_TESTS_NOT_REACHED",
            "compiler-lexer-pipeline", "COMPILER_TOKENIZATION_NOT_REACHED",
            "discrete-induction-proof", "DISCRETE_BASE_CASE_NOT_REACHED",
            "ai-classification-evaluation", "AI_CONFUSION_MATRIX_NOT_REACHED",
            "security-input-validation", "SEC_INPUT_CONSTRAINTS_NOT_REACHED"
        );

        for (String activityId : List.of(
                "systems-instruction-cycle", "os-sjf-scheduling", "network-packet-delivery",
                "se-ci-quality-gate", "compiler-lexer-pipeline", "discrete-induction-proof",
                "ai-classification-evaluation", "security-input-validation")) {
            var definition = service.loadDefinition(activityId);
            var specification = (SimulationActivitySpecification) definition.specification();
            if (alpha6FailureReasons.containsKey(activityId)) {
                var incomplete = service.submit(activityId, new SimulationActivityArtifact(List.of()));
                assertEquals(alpha6FailureReasons.get(activityId), incomplete.evaluation().reasonCode());
            }
            var submission = service.submit(activityId, new SimulationActivityArtifact(
                specification.actions().stream().map(action -> action.id()).toList()));
            assertEquals(ActivityType.SIMULATION, definition.type());
            assertTrue(submission.evaluation().passed(), activityId);
            assertEquals("SIMULATION_PASSED", submission.evaluation().reasonCode());
        }
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            assertEquals(13, scalar(statement, "select count(*) from activity_evaluation_result "
                + "where owner_id='student-simulation' and activity_type='SIMULATION'"));
            assertEquals(13, scalar(statement, "select count(*) from learning_events "
                + "where activity_type='SIMULATION'"));
        }
    }

    @Test
    void shouldLoadAndPersistTheBetaLabAndReadingActivities() throws Exception {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("beta-app.db"),
            tempDir.resolve("beta-demo.db"));
        new SqliteSchemaMigrator().migrate(databases.appDatabasePath());
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        var owner = (com.sqlteacher.application.event.LearningEventOwnerProvider) () -> "student-beta";
        var events = new DefaultLearningEventService(new JdbcLearningEventRecorder(connections), owner);
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(
            new LabActivityEvaluator(), new ReadingActivityEvaluator()));
        var service = new JdbcActivityLearningService(connections, owner, events, dispatcher,
            Clock.fixed(Instant.parse("2026-08-09T03:30:00Z"), ZoneOffset.UTC));

        var lab = service.loadDefinition("programming-debug-lab");
        var labSpec = (LabActivitySpecification) lab.specification();
        Map<String, String> observations = new java.util.LinkedHashMap<>();
        labSpec.steps().forEach(step -> observations.put(step.observationKey(), "已记录：" + step.title()));
        var labSubmission = service.submit(lab.id(), new LabActivityArtifact(
            labSpec.steps().stream().map(step -> step.id()).toList(), observations,
            "缺陷源于输入切分边界，修复后正常输入、空白输入和负数输入的固定回归均通过；"
                + "本次观测能够复现并解释错误，同时保留输出摘要，后续仍需增加异常编码、超长输入和取消路径样例。"));

        var reading = service.loadDefinition("tree-complexity-reading");
        var readingSpec = (ReadingActivitySpecification) reading.specification();
        var readingSubmission = service.submit(reading.id(),
            new ReadingActivityArtifact(true, Map.of("order", "根 左 右", "time", "O(n)")));

        assertEquals(ActivityType.LAB, lab.type());
        assertEquals(3, labSpec.steps().size());
        assertTrue(labSubmission.evaluation().passed());
        assertEquals(ActivityType.READING, reading.type());
        assertEquals("Apache-2.0", readingSpec.license());
        assertTrue(readingSubmission.evaluation().passed());
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            assertEquals(2, scalar(statement, "select count(*) from activity_evaluation_result "
                + "where owner_id='student-beta' and activity_type in ('LAB','READING')"));
        }
    }

    @Test
    void shouldVersionProjectSubmissionsAndExposeOnlyTheOwnersPortfolio() throws Exception {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("project-app.db"),
            tempDir.resolve("project-demo.db"));
        new SqliteSchemaMigrator().migrate(databases.appDatabasePath());
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        var activeOwner = new java.util.concurrent.atomic.AtomicReference<>("student-project");
        var owner = (com.sqlteacher.application.event.LearningEventOwnerProvider) activeOwner::get;
        var events = new DefaultLearningEventService(new JdbcLearningEventRecorder(connections), owner);
        var service = new JdbcActivityLearningService(connections, owner, events,
            new DefaultActivityEvaluationDispatcher(List.of(new ProjectActivityEvaluator())),
            Clock.fixed(Instant.parse("2026-08-09T04:00:00Z"), ZoneOffset.UTC));
        var specification = (ProjectActivitySpecification) service.loadDefinition("se-versioned-project").specification();
        List<String> milestones = specification.milestones().stream().map(item -> item.id()).toList();
        String evidence = "这是一段足够长且不包含源码的交付证据摘要，用于说明固定测试、验收结果、限制和可复核的项目交付过程。";
        String reflection = "本次实现仍有清晰限制，后续会根据教师反馈继续拆分模块、补足失败路径并更新交付证据。";

        assertEquals(1, service.nextSubmissionVersion("se-versioned-project"));
        service.submit("se-versioned-project", new ProjectActivityArtifact(1, milestones, evidence, reflection));
        assertEquals(2, service.nextSubmissionVersion("se-versioned-project"));
        assertThrows(com.sqlteacher.domain.SqlTeacherException.class, () -> service.submit("se-versioned-project",
            new ProjectActivityArtifact(1, milestones, evidence, reflection)));
        service.submit("se-versioned-project", new ProjectActivityArtifact(2, milestones, evidence, reflection));

        var portfolio = new JdbcProjectPortfolioService(connections, owner);
        assertEquals(List.of(2, 1), portfolio.listOwnEntries().stream()
            .map(com.sqlteacher.application.activity.ProjectPortfolioEntry::submissionVersion).toList());
        assertTrue(portfolio.exportOwnPortfolio(true).contains("PRIVATE_EXPORT"));
        assertThrows(SecurityException.class, () -> portfolio.exportOwnPortfolio(false));
        activeOwner.set("another-student");
        assertTrue(portfolio.listOwnEntries().isEmpty());
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
