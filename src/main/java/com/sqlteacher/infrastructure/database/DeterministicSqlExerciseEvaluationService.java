package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class DeterministicSqlExerciseEvaluationService implements SqlExerciseEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(DeterministicSqlExerciseEvaluationService.class);
    private static final int MAX_EVALUATION_ROWS = 5_000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final SqlRiskAnalysisService riskAnalysisService;
    private final Path evaluationDirectory;

    public DeterministicSqlExerciseEvaluationService(
        SqlRiskAnalysisService riskAnalysisService,
        SqlTeacherConfiguration configuration
    ) {
        this.riskAnalysisService = riskAnalysisService;
        this.evaluationDirectory = configuration.dataDirectory().resolve("exercise-evaluation").toAbsolutePath().normalize();
    }

    @Override
    public ExerciseEvaluationResult evaluate(
        ExerciseDefinition exercise,
        ExerciseDataset dataset,
        String submittedSql
    ) {
        long started = System.nanoTime();
        SqlRiskAnalysis risk = riskAnalysisService.analyze(submittedSql, DatabaseDialect.SQLITE);
        if (!risk.executable() || risk.multiStatement() || !"SELECT".equals(risk.statementType())) {
            return failure(
                started,
                "SQL_SAFETY_REJECTED",
                new EvaluationCriterionResult("safety", false, "只允许提交单条只读 SELECT 查询。")
            );
        }

        Path databasePath = null;
        try {
            Files.createDirectories(evaluationDirectory);
            databasePath = Files.createTempFile(evaluationDirectory, "evaluation-", ".db");
            initializeDataset(databasePath, dataset);
            QueryResult expected = executeQuery(databasePath, exercise.referenceSql());
            QueryResult actual = executeQuery(databasePath, submittedSql);
            if (expected.truncated() || actual.truncated()) {
                return failure(
                    started,
                    "RESULT_LIMIT_EXCEEDED",
                    new EvaluationCriterionResult("result_limit", false, "结果规模超过评测上限，请缩小查询范围。")
                );
            }
            List<EvaluationCriterionResult> criteria = evaluateCriteria(
                exercise.evaluationRule(), submittedSql, expected, actual
            );
            boolean passed = criteria.stream().allMatch(EvaluationCriterionResult::passed);
            return new ExerciseEvaluationResult(
                passed,
                criteria,
                passed ? "提交通过，结果满足题目要求。" : "提交未通过，请根据分项反馈调整查询。",
                elapsed(started),
                passed ? "" : "RESULT_MISMATCH"
            );
        } catch (SQLException error) {
            return failure(
                started,
                "SQL_EXECUTION_FAILED",
                new EvaluationCriterionResult("execution", false, "SQL 未能在评测数据集上执行，请检查语法和字段。")
            );
        } catch (IOException error) {
            return failure(
                started,
                "EVALUATION_ENVIRONMENT_FAILED",
                new EvaluationCriterionResult("environment", false, "暂时无法创建评测环境，请稍后重试。")
            );
        } finally {
            deleteQuietly(databasePath);
        }
    }

    private static List<EvaluationCriterionResult> evaluateCriteria(
        ExerciseEvaluationRule rule,
        String submittedSql,
        QueryResult expected,
        QueryResult actual
    ) {
        List<EvaluationCriterionResult> criteria = new ArrayList<>();
        if (rule.compareColumns()) {
            boolean passed = normalizeColumns(expected.columns()).equals(normalizeColumns(actual.columns()));
            criteria.add(new EvaluationCriterionResult(
                "columns", passed, passed ? "结果列满足要求。" : "结果列的数量、名称或顺序不符合要求。"
            ));
        }
        if (rule.compareRows()) {
            boolean passed = rowMultiset(expected.rows()).equals(rowMultiset(actual.rows()));
            criteria.add(new EvaluationCriterionResult(
                "rows", passed, passed ? "结果行和值满足要求。" : "结果行数或部分值不符合要求。"
            ));
            if (rule.rowOrderMatters()) {
                boolean ordered = expected.rows().equals(actual.rows());
                criteria.add(new EvaluationCriterionResult(
                    "order", ordered, ordered ? "结果顺序满足要求。" : "结果内容接近，但行顺序不符合要求。"
                ));
            }
        }
        if (rule.expectedRowCount() != null) {
            boolean passed = actual.rows().size() == rule.expectedRowCount();
            criteria.add(new EvaluationCriterionResult(
                "row_count", passed, passed ? "结果行数满足要求。" : "结果行数不符合题目要求。"
            ));
        }
        if (!rule.requiredSqlKeywords().isEmpty()) {
            List<String> missing = rule.requiredSqlKeywords().stream()
                .filter(keyword -> !containsSqlStructure(submittedSql, keyword))
                .toList();
            criteria.add(new EvaluationCriterionResult(
                "structure",
                missing.isEmpty(),
                missing.isEmpty() ? "SQL 结构满足要求。" : "查询尚未使用题目要求的 SQL 结构。"
            ));
        }
        return List.copyOf(criteria);
    }

    private static void initializeDataset(Path databasePath, ExerciseDataset dataset) throws SQLException {
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

    private static QueryResult executeQuery(Path databasePath, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement settings = connection.createStatement()) {
            settings.execute("pragma query_only = on");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_EVALUATION_ROWS + 1);
                try (ResultSet result = statement.executeQuery(sql)) {
                    ResultSetMetaData metadata = result.getMetaData();
                    int columnCount = metadata.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int index = 1; index <= columnCount; index++) {
                        columns.add(metadata.getColumnLabel(index));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (result.next()) {
                        if (rows.size() >= MAX_EVALUATION_ROWS) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(columnCount);
                        for (int index = 1; index <= columnCount; index++) {
                            row.add(normalizeValue(result.getObject(index)));
                        }
                        rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
                    }
                    return new QueryResult(List.copyOf(columns), List.copyOf(rows), truncated);
                }
            }
        }
    }

    private static List<String> normalizeColumns(List<String> columns) {
        return columns.stream().map(column -> column.trim().toLowerCase(Locale.ROOT)).toList();
    }

    private static Map<List<Object>, Long> rowMultiset(List<List<Object>> rows) {
        Map<List<Object>, Long> counts = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            counts.merge(row, 1L, Long::sum);
        }
        return counts;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros();
            } catch (NumberFormatException ignored) {
                return number.toString();
            }
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    private static boolean containsSqlStructure(String sql, String keyword) {
        String normalized = maskCommentsAndLiterals(sql).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String phrase = keyword.toUpperCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        return Pattern.compile("(?<![A-Z0-9_])" + Pattern.quote(phrase) + "(?![A-Z0-9_])")
            .matcher(normalized)
            .find();
    }

    private static String maskCommentsAndLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        char quote = '\0';
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    result.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    index++;
                    blockComment = false;
                    result.append(' ');
                }
                continue;
            }
            if (quote == '\0' && current == '-' && next == '-') {
                index++;
                lineComment = true;
                result.append(' ');
            } else if (quote == '\0' && current == '/' && next == '*') {
                index++;
                blockComment = true;
                result.append(' ');
            } else if (quote == '\0' && (current == '\'' || current == '"' || current == '`')) {
                quote = current;
                result.append(' ');
            } else if (quote != '\0') {
                result.append(' ');
                if (current == quote && next == quote) {
                    result.append(' ');
                    index++;
                } else if (current == quote) {
                    quote = '\0';
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static ExerciseEvaluationResult failure(
        long started,
        String errorCode,
        EvaluationCriterionResult criterion
    ) {
        return new ExerciseEvaluationResult(
            false, List.of(criterion), "提交未通过，请根据反馈修改后重试。", elapsed(started), errorCode
        );
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static void deleteQuietly(Path databasePath) {
        if (databasePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(databasePath);
            Files.deleteIfExists(Path.of(databasePath + "-wal"));
            Files.deleteIfExists(Path.of(databasePath + "-shm"));
        } catch (IOException error) {
            log.warn("Failed to remove temporary exercise evaluation database", error);
        }
    }

    private record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {
    }
}
