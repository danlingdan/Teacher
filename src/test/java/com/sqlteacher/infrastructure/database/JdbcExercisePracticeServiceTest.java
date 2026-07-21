package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.ExerciseHint;
import com.sqlteacher.application.exercise.ExerciseSession;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.domain.exercise.ExerciseAttemptStatus;
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
        assertEquals(2, count(fixture.appDb(), "exercise_attempts"));
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

        assertEquals(6, countStudents(fixture.sessionDatabase(session.id())));
        assertEquals(0, reset.hintsUsed());
        assertEquals(1, first.level());
        assertEquals(2, second.level());
        assertTrue(second.exhausted());
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

    private Fixture fixture() {
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
            configuration
        );
        return new Fixture(service, appDb, tempDir.resolve("exercise-sessions"));
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

    private record Fixture(JdbcExercisePracticeService service, Path appDb, Path sessionDirectory) {
        Path sessionDatabase(String sessionId) {
            return sessionDirectory.resolve(sessionId + ".db");
        }
    }
}
