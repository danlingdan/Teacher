package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.ExerciseHint;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.ExerciseSession;
import com.sqlteacher.application.exercise.ExerciseView;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseAttemptStatus;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdbcExercisePracticeService implements ExercisePracticeService {
    private static final Logger log = LoggerFactory.getLogger(JdbcExercisePracticeService.class);
    static final int MAX_RESULT_ROWS = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final JdbcConnectionFactory connectionFactory;
    private final ExerciseManagementService managementService;
    private final SqlRiskAnalysisService riskAnalysisService;
    private final SqlExerciseEvaluationService evaluationService;
    private final SqlResultMapper resultMapper;
    private final ExerciseAttemptCodec attemptCodec;
    private final Path sessionDirectory;

    public JdbcExercisePracticeService(
        JdbcConnectionFactory connectionFactory,
        ExerciseManagementService managementService,
        SqlRiskAnalysisService riskAnalysisService,
        SqlExerciseEvaluationService evaluationService,
        SqlResultMapper resultMapper,
        SqlTeacherConfiguration configuration
    ) {
        this.connectionFactory = connectionFactory;
        this.managementService = managementService;
        this.riskAnalysisService = riskAnalysisService;
        this.evaluationService = evaluationService;
        this.resultMapper = resultMapper;
        this.attemptCodec = new ExerciseAttemptCodec();
        this.sessionDirectory = configuration.dataDirectory().resolve("exercise-sessions").toAbsolutePath().normalize();
    }

    @Override
    public ExerciseSession start(String exerciseId) {
        ExerciseDefinition exercise = requireAvailableExercise(exerciseId);
        ExerciseDataset dataset = requireDataset(exercise.datasetId());
        String sessionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        Path databasePath = sessionDatabase(sessionId);
        try {
            initializeDataset(databasePath, dataset);
            try (Connection connection = connectionFactory.open("app");
                 PreparedStatement statement = connection.prepareStatement("""
                     insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used)
                     values (?, ?, ?, ?, 0)
                     """)) {
                statement.setString(1, sessionId);
                statement.setString(2, exercise.id());
                statement.setInt(3, exercise.version());
                statement.setString(4, startedAt.toString());
                statement.executeUpdate();
            }
            return toSession(sessionId, exercise, startedAt, 0, false);
        } catch (SQLException | IOException error) {
            deleteSessionDatabase(databasePath);
            throw new SqlTeacherException("EXERCISE_SESSION_START_FAILED", "Failed to start exercise session", error);
        }
    }

    @Override
    public ExerciseSession reset(String sessionId) {
        SessionRecord session = requireSession(sessionId, true);
        ExerciseDefinition exercise = requireCurrentExercise(session);
        ExerciseDataset dataset = requireDataset(exercise.datasetId());
        Path databasePath = sessionDatabase(session.id());
        deleteSessionDatabase(databasePath);
        try {
            initializeDataset(databasePath, dataset);
            return toSession(session.id(), exercise, session.startedAt(), session.hintsUsed(), false);
        } catch (SQLException | IOException error) {
            deleteSessionDatabase(databasePath);
            throw new SqlTeacherException("EXERCISE_SESSION_RESET_FAILED", "Failed to reset exercise dataset", error);
        }
    }

    @Override
    public ExerciseAttemptResult run(String sessionId, String sql) {
        SessionRecord session = requireSession(sessionId, true);
        requireCurrentExercise(session);
        SqlExecutionResult execution = executeStudentQuery(session.id(), sql);
        Instant occurredAt = Instant.now();
        String attemptId = UUID.randomUUID().toString();
        recordAttempt(
            attemptId, session.id(), ExerciseAttemptStatus.RUN, sql, execution, null, occurredAt,
            execution.success() ? "" : "SQL_EXECUTION_FAILED"
        );
        return new ExerciseAttemptResult(
            attemptId, session.id(), ExerciseAttemptStatus.RUN, execution, null, occurredAt
        );
    }

    @Override
    public ExerciseAttemptResult submit(String sessionId, String sql) {
        SessionRecord session = requireSession(sessionId, true);
        ExerciseDefinition exercise = requireCurrentExercise(session);
        ExerciseDataset dataset = requireDataset(exercise.datasetId());
        SqlExecutionResult execution = executeStudentQuery(session.id(), sql);
        ExerciseEvaluationResult evaluation = execution.success()
            ? evaluationService.evaluate(exercise, dataset, sql)
            : failedExecutionEvaluation(execution.duration());
        ExerciseAttemptStatus status = evaluation.passed()
            ? ExerciseAttemptStatus.PASSED
            : ExerciseAttemptStatus.FAILED;
        Instant occurredAt = Instant.now();
        String attemptId = UUID.randomUUID().toString();
        recordAttempt(attemptId, session.id(), status, sql, execution, evaluation, occurredAt, evaluation.errorCode());
        if (evaluation.passed()) {
            completeSession(session.id(), occurredAt);
            deleteSessionDatabase(sessionDatabase(session.id()));
        }
        return new ExerciseAttemptResult(attemptId, session.id(), status, execution, evaluation, occurredAt);
    }

    @Override
    public ExerciseHint requestHint(String sessionId) {
        SessionRecord session = requireSession(sessionId, true);
        ExerciseDefinition exercise = requireCurrentExercise(session);
        if (exercise.hints().isEmpty()) {
            throw new SqlTeacherException("EXERCISE_HINT_UNAVAILABLE", "No hint is available for this exercise");
        }
        int nextCount = Math.min(session.hintsUsed() + 1, exercise.hints().size());
        if (nextCount > session.hintsUsed()) {
            try (Connection connection = connectionFactory.open("app");
                 PreparedStatement statement = connection.prepareStatement(
                     "update exercise_sessions set hints_used = ? where id = ? and completed_at is null"
                 )) {
                statement.setInt(1, nextCount);
                statement.setString(2, session.id());
                if (statement.executeUpdate() != 1) {
                    throw sessionClosed();
                }
            } catch (SQLException error) {
                throw new SqlTeacherException("EXERCISE_HINT_FAILED", "Failed to record exercise hint", error);
            }
        }
        return new ExerciseHint(
            nextCount,
            exercise.hints().get(nextCount - 1),
            nextCount >= exercise.hints().size()
        );
    }

    @Override
    public void close(String sessionId) {
        SessionRecord session = requireSession(sessionId, false);
        if (!session.completed()) {
            completeSession(session.id(), Instant.now());
        }
        deleteSessionDatabase(sessionDatabase(session.id()));
    }

    public void shutdown() {
        try (Connection connection = connectionFactory.open("app")) {
            ExerciseSessionRuntimeCleaner.closeActiveSessions(connection, Instant.now());
            ExerciseSessionRuntimeCleaner.deleteSessionFiles(sessionDirectory);
        } catch (SQLException | IOException error) {
            log.warn("Failed to clean exercise sessions during application shutdown", error);
        }
    }

    private SqlExecutionResult executeStudentQuery(String sessionId, String sql) {
        SqlRiskAnalysis risk = riskAnalysisService.analyze(sql, DatabaseDialect.SQLITE);
        if (!risk.executable() || risk.multiStatement() || !"SELECT".equals(risk.statementType())) {
            return new SqlExecutionResult(
                false, List.of(), List.of(), 0, false,
                "练习环境只允许执行单条 SELECT 查询。", Duration.ZERO
            );
        }
        long started = System.nanoTime();
        try {
            SqliteDriver.ensureLoaded();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sessionDatabase(sessionId));
                 Statement settings = connection.createStatement()) {
                settings.execute("pragma query_only = on");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.setMaxRows(MAX_RESULT_ROWS + 1);
                    try (ResultSet rows = statement.executeQuery()) {
                        return resultMapper.mapQueryResult(
                            rows, Duration.ofNanos(System.nanoTime() - started), MAX_RESULT_ROWS
                        );
                    }
                }
            }
        } catch (SQLException error) {
            return new SqlExecutionResult(
                false, List.of(), List.of(), 0, false,
                "SQL 执行失败，请检查语法、表名和字段名。",
                Duration.ofNanos(System.nanoTime() - started)
            );
        }
    }

    private void recordAttempt(
        String attemptId,
        String sessionId,
        ExerciseAttemptStatus status,
        String sql,
        SqlExecutionResult execution,
        ExerciseEvaluationResult evaluation,
        Instant occurredAt,
        String errorCode
    ) {
        String insert = """
            insert into exercise_attempts(
                id, session_id, status, sql_text, execution_success, passed, duration_ms,
                result_columns_json, result_rows_json, feedback_json, error_code, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, attemptId);
            statement.setString(2, sessionId);
            statement.setString(3, status.name());
            statement.setString(4, sql == null ? "" : sql);
            statement.setBoolean(5, execution.success());
            if (evaluation == null) {
                statement.setNull(6, Types.INTEGER);
            } else {
                statement.setBoolean(6, evaluation.passed());
            }
            statement.setLong(7, durationMillis(execution, evaluation));
            statement.setString(8, attemptCodec.encode(execution.columns()));
            statement.setString(9, attemptCodec.encode(execution.rows()));
            statement.setString(10, attemptCodec.encode(feedback(evaluation)));
            statement.setString(11, errorCode == null || errorCode.isBlank() ? null : errorCode);
            statement.setString(12, occurredAt.toString());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_ATTEMPT_RECORD_FAILED", "Failed to record exercise attempt", error);
        }
    }

    private static long durationMillis(SqlExecutionResult execution, ExerciseEvaluationResult evaluation) {
        Duration duration = execution.duration();
        if (evaluation != null) {
            duration = duration.plus(evaluation.duration());
        }
        return Math.max(0, duration.toMillis());
    }

    private static List<String> feedback(ExerciseEvaluationResult evaluation) {
        if (evaluation == null) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        if (!evaluation.feedback().isBlank()) {
            messages.add(evaluation.feedback());
        }
        messages.addAll(evaluation.criteria().stream().map(EvaluationCriterionResult::feedback).toList());
        return List.copyOf(messages);
    }

    private static ExerciseEvaluationResult failedExecutionEvaluation(Duration duration) {
        return new ExerciseEvaluationResult(
            false,
            List.of(new EvaluationCriterionResult("execution", false, "SQL 未能成功执行，请先修正语法或字段。")),
            "本次提交未通过：SQL 执行失败。",
            duration,
            "SQL_EXECUTION_FAILED"
        );
    }

    private SessionRecord requireSession(String sessionId, boolean active) {
        String normalizedId = validateSessionId(sessionId);
        String sql = "select id, exercise_id, exercise_version, started_at, hints_used, completed_at from exercise_sessions where id = ?";
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SqlTeacherException("EXERCISE_SESSION_NOT_FOUND", "Exercise session not found");
                }
                SessionRecord session = new SessionRecord(
                    row.getString("id"), row.getString("exercise_id"), row.getInt("exercise_version"),
                    Instant.parse(row.getString("started_at")), row.getInt("hints_used"),
                    row.getString("completed_at") != null
                );
                if (active && session.completed()) {
                    throw sessionClosed();
                }
                return session;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_SESSION_READ_FAILED", "Failed to read exercise session", error);
        }
    }

    private ExerciseDefinition requireCurrentExercise(SessionRecord session) {
        ExerciseDefinition exercise = managementService.findDefinition(session.exerciseId())
            .orElseThrow(() -> new SqlTeacherException("EXERCISE_NOT_FOUND", "Exercise not found"));
        if (exercise.version() != session.exerciseVersion()) {
            throw new SqlTeacherException(
                "EXERCISE_SESSION_STALE", "The exercise changed after this session started; start a new session"
            );
        }
        return exercise;
    }

    private ExerciseDefinition requireAvailableExercise(String exerciseId) {
        ExerciseDefinition exercise = managementService.findDefinition(exerciseId)
            .orElseThrow(() -> new SqlTeacherException("EXERCISE_NOT_FOUND", "Exercise not found"));
        if (!exercise.enabled()) {
            throw new SqlTeacherException("EXERCISE_DISABLED", "Exercise is not available");
        }
        return exercise;
    }

    private ExerciseDataset requireDataset(String datasetId) {
        return managementService.listDatasets().stream()
            .filter(dataset -> dataset.id().equals(datasetId))
            .findFirst()
            .orElseThrow(() -> new SqlTeacherException("EXERCISE_DATASET_NOT_FOUND", "Exercise dataset not found"));
    }

    private void initializeDataset(Path databasePath, ExerciseDataset dataset) throws SQLException, IOException {
        Files.createDirectories(sessionDirectory);
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : SqlScriptSplitter.split(dataset.setupSql())) {
                    statement.execute(sql);
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private void completeSession(String sessionId, Instant completedAt) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(
                 "update exercise_sessions set completed_at = ? where id = ? and completed_at is null"
             )) {
            statement.setString(1, completedAt.toString());
            statement.setString(2, sessionId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_SESSION_CLOSE_FAILED", "Failed to close exercise session", error);
        }
    }

    private Path sessionDatabase(String sessionId) {
        String normalizedId = validateSessionId(sessionId);
        Path path = sessionDirectory.resolve(normalizedId + ".db").normalize();
        if (!path.getParent().equals(sessionDirectory)) {
            throw new IllegalArgumentException("Invalid session ID");
        }
        return path;
    }

    private static String validateSessionId(String sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        return UUID.fromString(sessionId.trim()).toString();
    }

    private static void deleteSessionDatabase(Path databasePath) {
        try {
            Files.deleteIfExists(databasePath);
            Files.deleteIfExists(Path.of(databasePath + "-wal"));
            Files.deleteIfExists(Path.of(databasePath + "-shm"));
        } catch (IOException error) {
            throw new SqlTeacherException("EXERCISE_SESSION_CLEANUP_FAILED", "Failed to clean exercise dataset", error);
        }
    }

    private static ExerciseSession toSession(
        String id, ExerciseDefinition exercise, Instant startedAt, int hintsUsed, boolean completed
    ) {
        return new ExerciseSession(
            id,
            new ExerciseView(
                exercise.id(), exercise.title(), exercise.description(), exercise.knowledgePoint(),
                exercise.difficulty(), exercise.version()
            ),
            startedAt,
            hintsUsed,
            completed
        );
    }

    private static SqlTeacherException sessionClosed() {
        return new SqlTeacherException("EXERCISE_SESSION_CLOSED", "Exercise session is already closed");
    }

    private record SessionRecord(
        String id,
        String exerciseId,
        int exerciseVersion,
        Instant startedAt,
        int hintsUsed,
        boolean completed
    ) {
    }
}
