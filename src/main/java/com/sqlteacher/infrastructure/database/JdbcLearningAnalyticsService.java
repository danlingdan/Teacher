package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.analytics.AnalyticsCsvExport;
import com.sqlteacher.application.analytics.AnalyticsFilter;
import com.sqlteacher.application.analytics.AnalyticsOverview;
import com.sqlteacher.application.analytics.ErrorAnalytics;
import com.sqlteacher.application.analytics.ExerciseAnalyticsRow;
import com.sqlteacher.application.analytics.KnowledgePointAnalytics;
import com.sqlteacher.application.analytics.LearningAnalyticsReport;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class JdbcLearningAnalyticsService implements LearningAnalyticsService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC);
    private final JdbcConnectionFactory connectionFactory;
    private final Clock clock;

    public JdbcLearningAnalyticsService(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, Clock.systemUTC());
    }

    JdbcLearningAnalyticsService(JdbcConnectionFactory connectionFactory, Clock clock) {
        this.connectionFactory = connectionFactory;
        this.clock = clock;
    }

    @Override
    public LearningAnalyticsReport analyze(AnalyticsFilter requestedFilter) {
        AnalyticsFilter filter = requestedFilter == null ? AnalyticsFilter.all() : requestedFilter;
        try (Connection connection = connectionFactory.open("app")) {
            List<ExerciseRecord> exercises = loadExercises(connection, filter);
            List<AttemptRecord> attempts = loadAttempts(connection, filter);
            int sessions = countSessions(connection, filter);
            return buildReport(filter, exercises, attempts, sessions, clock.instant());
        } catch (SQLException | IllegalArgumentException error) {
            throw new SqlTeacherException("LEARNING_ANALYTICS_READ_FAILED", "Failed to calculate learning analytics", error);
        }
    }

    @Override
    public AnalyticsCsvExport exportCsv(AnalyticsFilter filter) {
        LearningAnalyticsReport report = analyze(filter);
        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append("SQLTeacher 学情导出\r\n");
        row(csv, "生成时间(UTC)", report.generatedAt().toString());
        row(csv, "开始时间(含)", value(report.filter().startInclusive()));
        row(csv, "结束时间(不含)", value(report.filter().endExclusive()));
        row(csv, "题目筛选", value(report.filter().exerciseId()));
        row(csv, "知识点筛选", value(report.filter().knowledgePoint()));
        row(csv, "错误类型筛选", value(report.filter().errorCode()));
        csv.append("统计口径,通过率=通过提交/全部提交;完成率=筛选范围内至少通过一次的题目/启用题目;平均尝试=尝试数/已完成题目\r\n\r\n");
        csv.append("会话,尝试,提交,通过提交,通过率,平均尝试,平均提交耗时(ms),完成题目,题目总数,完成率\r\n");
        AnalyticsOverview overview = report.overview();
        row(csv, overview.sessions(), overview.attempts(), overview.submissions(), overview.passedSubmissions(),
            decimal(overview.passRate()), decimal(overview.averageAttemptsPerCompletedExercise()),
            overview.averageSubmissionDuration().toMillis(), overview.completedExercises(), overview.totalExercises(),
            decimal(overview.completionRate()));
        csv.append("\r\n题目ID,题目,知识点,尝试,提交,通过提交,失败提交,通过率,是否完成,最后尝试时间(UTC)\r\n");
        for (ExerciseAnalyticsRow item : report.exercises()) {
            row(csv, item.exerciseId(), item.title(), item.knowledgePoint(), item.attempts(), item.submissions(),
                item.passedSubmissions(), item.failedSubmissions(), decimal(item.passRate()),
                item.completed() ? "是" : "否", item.lastAttempt().map(Instant::toString).orElse(""));
        }
        csv.append("\r\n错误类型,次数\r\n");
        for (ErrorAnalytics error : report.commonErrors()) {
            row(csv, error.errorCode(), error.count());
        }
        csv.append("\r\n知识点,尝试,失败提交,完成题目,题目总数,薄弱度\r\n");
        for (KnowledgePointAnalytics point : report.knowledgePoints()) {
            row(csv, point.knowledgePoint(), point.attempts(), point.failedSubmissions(),
                point.completedExercises(), point.totalExercises(), decimal(point.weaknessRate()));
        }
        return new AnalyticsCsvExport(
            "sqlteacher-analytics-" + FILE_TIME.format(report.generatedAt()) + ".csv",
            csv.toString(),
            report.generatedAt()
        );
    }

    private static List<ExerciseRecord> loadExercises(Connection connection, AnalyticsFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("select id, title, knowledge_point from exercises where enabled = 1");
        List<String> parameters = new ArrayList<>();
        if (filter.exerciseId() != null) {
            sql.append(" and id = ?");
            parameters.add(filter.exerciseId());
        }
        if (filter.knowledgePoint() != null) {
            sql.append(" and knowledge_point = ?");
            parameters.add(filter.knowledgePoint());
        }
        sql.append(" order by knowledge_point, title");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<ExerciseRecord> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ExerciseRecord(
                        rows.getString("id"), rows.getString("title"), rows.getString("knowledge_point")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static List<AttemptRecord> loadAttempts(Connection connection, AnalyticsFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("""
            select a.id, a.session_id, s.exercise_id, a.status, a.duration_ms, a.error_code, a.created_at
            from exercise_attempts a
            join exercise_sessions s on s.id = a.session_id
            join exercises e on e.id = s.exercise_id
            where e.enabled = 1
            """);
        List<String> parameters = new ArrayList<>();
        appendFilters(sql, parameters, filter, "a.created_at", true);
        sql.append(" order by a.created_at, a.id");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<AttemptRecord> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new AttemptRecord(
                        rows.getString("id"), rows.getString("session_id"), rows.getString("exercise_id"),
                        rows.getString("status"), rows.getLong("duration_ms"), rows.getString("error_code"),
                        Instant.parse(rows.getString("created_at"))
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static int countSessions(Connection connection, AnalyticsFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("""
            select count(distinct s.id)
            from exercise_sessions s
            join exercises e on e.id = s.exercise_id
            where e.enabled = 1
            """);
        List<String> parameters = new ArrayList<>();
        appendFilters(sql, parameters, filter, "s.started_at", false);
        if (filter.errorCode() != null) {
            sql.append(" and exists (select 1 from exercise_attempts ae where ae.session_id = s.id and ae.error_code = ?)");
            parameters.add(filter.errorCode());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static void appendFilters(
        StringBuilder sql,
        List<String> parameters,
        AnalyticsFilter filter,
        String timeColumn,
        boolean includeError
    ) {
        if (filter.startInclusive() != null) {
            sql.append(" and ").append(timeColumn).append(" >= ?");
            parameters.add(filter.startInclusive().toString());
        }
        if (filter.endExclusive() != null) {
            sql.append(" and ").append(timeColumn).append(" < ?");
            parameters.add(filter.endExclusive().toString());
        }
        if (filter.exerciseId() != null) {
            sql.append(" and e.id = ?");
            parameters.add(filter.exerciseId());
        }
        if (filter.knowledgePoint() != null) {
            sql.append(" and e.knowledge_point = ?");
            parameters.add(filter.knowledgePoint());
        }
        if (includeError && filter.errorCode() != null) {
            sql.append(" and a.error_code = ?");
            parameters.add(filter.errorCode());
        }
    }

    private static LearningAnalyticsReport buildReport(
        AnalyticsFilter filter,
        List<ExerciseRecord> exercises,
        List<AttemptRecord> attempts,
        int sessions,
        Instant generatedAt
    ) {
        Map<String, ExerciseAccumulator> byExercise = new LinkedHashMap<>();
        for (ExerciseRecord exercise : exercises) {
            byExercise.put(exercise.id(), new ExerciseAccumulator(exercise));
        }
        Map<String, Integer> errors = new LinkedHashMap<>();
        long submissionDuration = 0;
        int submissions = 0;
        int passedSubmissions = 0;
        for (AttemptRecord attempt : attempts) {
            ExerciseAccumulator accumulator = byExercise.get(attempt.exerciseId());
            if (accumulator == null) {
                continue;
            }
            accumulator.accept(attempt);
            if (attempt.submission()) {
                submissions++;
                submissionDuration += attempt.durationMs();
                if (attempt.passed()) {
                    passedSubmissions++;
                }
            }
            if (attempt.errorCode() != null && !attempt.errorCode().isBlank()) {
                errors.merge(attempt.errorCode(), 1, Integer::sum);
            }
        }
        List<ExerciseAnalyticsRow> exerciseRows = byExercise.values().stream()
            .map(ExerciseAccumulator::toRow)
            .sorted(Comparator.comparing(ExerciseAnalyticsRow::completed)
                .thenComparing(ExerciseAnalyticsRow::failedSubmissions, Comparator.reverseOrder())
                .thenComparing(ExerciseAnalyticsRow::title))
            .toList();
        int completed = (int) exerciseRows.stream().filter(ExerciseAnalyticsRow::completed).count();
        AnalyticsOverview overview = new AnalyticsOverview(
            sessions,
            attempts.size(),
            submissions,
            passedSubmissions,
            ratio(passedSubmissions, submissions),
            completed == 0 ? 0 : (double) attempts.size() / completed,
            Duration.ofMillis(submissions == 0 ? 0 : Math.round((double) submissionDuration / submissions)),
            completed,
            exercises.size(),
            ratio(completed, exercises.size())
        );
        List<ErrorAnalytics> errorRows = errors.entrySet().stream()
            .map(entry -> new ErrorAnalytics(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingInt(ErrorAnalytics::count).reversed().thenComparing(ErrorAnalytics::errorCode))
            .toList();
        List<KnowledgePointAnalytics> knowledgeRows = buildKnowledgeRows(exerciseRows);
        return new LearningAnalyticsReport(filter, generatedAt, overview, exerciseRows, errorRows, knowledgeRows);
    }

    private static List<KnowledgePointAnalytics> buildKnowledgeRows(List<ExerciseAnalyticsRow> exercises) {
        Map<String, List<ExerciseAnalyticsRow>> grouped = new LinkedHashMap<>();
        for (ExerciseAnalyticsRow exercise : exercises) {
            grouped.computeIfAbsent(exercise.knowledgePoint(), ignored -> new ArrayList<>()).add(exercise);
        }
        return grouped.entrySet().stream().map(entry -> {
            List<ExerciseAnalyticsRow> rows = entry.getValue();
            int attempts = rows.stream().mapToInt(ExerciseAnalyticsRow::attempts).sum();
            int failures = rows.stream().mapToInt(ExerciseAnalyticsRow::failedSubmissions).sum();
            int submissions = rows.stream().mapToInt(ExerciseAnalyticsRow::submissions).sum();
            int completed = (int) rows.stream().filter(ExerciseAnalyticsRow::completed).count();
            double failureRate = ratio(failures, submissions);
            double incompleteRate = 1 - ratio(completed, rows.size());
            double weakness = Math.min(1, failureRate * 0.6 + incompleteRate * 0.4);
            return new KnowledgePointAnalytics(entry.getKey(), attempts, failures, completed, rows.size(), weakness);
        }).sorted(Comparator.comparingDouble(KnowledgePointAnalytics::weaknessRate).reversed()
            .thenComparing(KnowledgePointAnalytics::knowledgePoint)).toList();
    }

    private static void bind(PreparedStatement statement, List<String> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setString(index + 1, parameters.get(index));
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static void row(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(escape(String.valueOf(values[index])));
        }
        csv.append("\r\n");
    }

    private static String escape(String value) {
        String normalized = value.replace("\r", " ").replace("\n", " ");
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    private static String value(Object value) {
        return value == null ? "全部" : value.toString();
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private record ExerciseRecord(String id, String title, String knowledgePoint) {
    }

    private record AttemptRecord(
        String id,
        String sessionId,
        String exerciseId,
        String status,
        long durationMs,
        String errorCode,
        Instant createdAt
    ) {
        boolean submission() {
            return "PASSED".equals(status) || "FAILED".equals(status);
        }

        boolean passed() {
            return "PASSED".equals(status);
        }
    }

    private static final class ExerciseAccumulator {
        private final ExerciseRecord exercise;
        private int attempts;
        private int submissions;
        private int passed;
        private int failed;
        private Instant lastAttempt;

        private ExerciseAccumulator(ExerciseRecord exercise) {
            this.exercise = exercise;
        }

        private void accept(AttemptRecord attempt) {
            attempts++;
            if (attempt.submission()) {
                submissions++;
                if (attempt.passed()) {
                    passed++;
                } else {
                    failed++;
                }
            }
            if (lastAttempt == null || attempt.createdAt().isAfter(lastAttempt)) {
                lastAttempt = attempt.createdAt();
            }
        }

        private ExerciseAnalyticsRow toRow() {
            return new ExerciseAnalyticsRow(
                exercise.id(), exercise.title(), exercise.knowledgePoint(), attempts, submissions, passed, failed,
                ratio(passed, submissions), passed > 0, lastAttempt
            );
        }
    }
}
