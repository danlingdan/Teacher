package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.mock.MockLearningEventService;
import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.ExerciseHint;
import com.sqlteacher.application.exercise.ExerciseSession;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.domain.exercise.ExerciseAttemptStatus;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExercisePracticeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldRunOnlySelectAndPersistAttempts() throws Exception {
        Fixture fixture = fixture();
        ExerciseSession session = fixture.service().start("query-02");

        ExerciseAttemptResult query = fixture.service().run(session.id(), "select name from student order by id");
        ExerciseAttemptResult mutation = fixture.service().run(session.id(), "delete from student");

        assertTrue(query.execution().success());
        assertEquals(6, query.execution().rows().size());
        assertFalse(mutation.execution().success());
        assertTrue(mutation.execution().message().contains("只接受单条 SELECT"));
        assertEquals(2, count(fixture.appDb(), "exercise_attempts"));
        assertEquals(6, countStudents(fixture.sessionDatabase(session.id())));
    }

    @Test
    void developerModeShouldNotBypassQueryExerciseContract() throws Exception {
        Fixture fixture = fixture(true);
        ExerciseSession session = fixture.service().start("query-02");

        ExerciseAttemptResult mutation = fixture.service().run(
            session.id(), "delete from student where id = 1"
        );

        assertFalse(mutation.execution().success());
        assertEquals(6, countStudents(fixture.sessionDatabase(session.id())));
    }

    @Test
    void shouldResetDatasetAndAdvanceHints() throws Exception {
        Fixture fixture = fixture();
        ExerciseSession session = fixture.service().start("query-02");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.sessionDatabase(session.id()));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from student where id = 1");
        }

        ExerciseSession reset = fixture.service().reset(session.id());
        ExerciseHint first = fixture.service().requestHint(session.id());
        ExerciseHint second = fixture.service().requestHint(session.id());
        ExerciseHint third = fixture.service().requestHint(session.id());

        assertEquals(6, countStudents(fixture.sessionDatabase(session.id())));
        assertEquals(0, reset.hintsUsed());
        assertEquals(1, first.level());
        assertEquals(2, second.level());
        assertFalse(second.exhausted());
        assertEquals(3, third.level());
        assertTrue(third.exhausted());
        assertTrue(session.exercise().schemaSummary().contains("student（id、name、class_name、score）"));
    }

    @Test
    void shouldClosePassingSubmissionAndDeleteIsolatedDatabase() {
        Fixture fixture = fixture();
        ExerciseSession session = fixture.service().start("query-02");

        ExerciseAttemptResult result = fixture.service().submit(
            session.id(), "select name from student order by id"
        );

        assertEquals(ExerciseAttemptStatus.PASSED, result.status());
        assertTrue(result.evaluation().passed());
        assertFalse(Files.exists(fixture.sessionDatabase(session.id())));
    }

    @Test
    void shouldPersistTheCurrentLearningOwnerOnNewSessions() throws Exception {
        Fixture fixture = fixture();
        var service = new JdbcExercisePracticeService(
            fixture.connections(), new JdbcExerciseManagementService(fixture.connections()),
            new DefaultSqlRiskAnalysisService(), (exercise, dataset, sql) -> new ExerciseEvaluationResult(
                true, List.of(), "通过", Duration.ZERO, ""), new SqlResultMapper(), fixture.configuration(),
            safetyMode(false), new MockLearningEventService(), () -> "student-42");

        ExerciseSession session = service.start("query-02");

        try (Connection connection = fixture.connections().open("app");
             var statement = connection.prepareStatement("select owner_id from exercise_sessions where id=?")) {
            statement.setString(1, session.id());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("student-42", row.getString(1));
            }
        }
    }

    @Test
    void shouldCloseActiveSessionsAndDeleteDatabasesOnShutdown() throws Exception {
        Fixture fixture = fixture();
        ExerciseSession session = fixture.service().start("query-02");
        assertTrue(Files.exists(fixture.sessionDatabase(session.id())));

        fixture.service().shutdown();

        assertFalse(Files.exists(fixture.sessionDatabase(session.id())));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.appDb());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select completed_at from exercise_sessions where id = '" + session.id() + "'"
             )) {
            assertTrue(result.next());
            assertTrue(result.getString(1) != null);
        }
    }

    private Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean unrestricted) {
        Path appDb = tempDir.resolve("app.db");
        Path demoDb = tempDir.resolve("demo.db");
        DatabaseConfiguration databases = new DatabaseConfiguration(appDb, demoDb);
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            databases,
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test"
            )
        );
        new SqliteAppDatabaseInitializer(configuration).initialize();
        JdbcConnectionFactory connections = new JdbcConnectionFactory(databases);
        JdbcExerciseManagementService management = new JdbcExerciseManagementService(connections);
        SqlExerciseEvaluationService evaluator = (exercise, dataset, sql) -> new ExerciseEvaluationResult(
            true,
            List.of(new EvaluationCriterionResult("test", true, "测试评测通过。")),
            "通过",
            Duration.ofMillis(1),
            ""
        );
        JdbcExercisePracticeService service = new JdbcExercisePracticeService(
            connections,
            management,
            new DefaultSqlRiskAnalysisService(),
            evaluator,
            new SqlResultMapper(),
            configuration,
            safetyMode(unrestricted),
            new MockLearningEventService()
        );
        return new Fixture(service, appDb, tempDir.resolve("exercise-sessions"), connections, configuration);
    }

    private static SqlSafetyModeService safetyMode(boolean unrestricted) {
        return new SqlSafetyModeService() {
            @Override
            public boolean isUnrestrictedModeEnabled() {
                return unrestricted;
            }

            @Override
            public void setUnrestrictedModeEnabled(boolean enabled) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static int count(Path database, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int countStudents(Path database) throws Exception {
        return count(database, "student");
    }

    private record Fixture(JdbcExercisePracticeService service, Path appDb, Path sessionDirectory,
                           JdbcConnectionFactory connections, SqlTeacherConfiguration configuration) {
        Path sessionDatabase(String sessionId) {
            return sessionDirectory.resolve(sessionId + ".db");
        }
    }
}
