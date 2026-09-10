package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.exercise.EvaluationCriterionResult;
import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.ResultComparison;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import com.sqlteacher.domain.exercise.ExerciseRevealMode;
import com.sqlteacher.domain.exercise.ExerciseType;
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
import java.util.Objects;
import java.util.Set;

public final class DeterministicSqlExerciseEvaluationService implements SqlExerciseEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(DeterministicSqlExerciseEvaluationService.class);
    private static final int MAX_EVALUATION_ROWS = 5_000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    /** Caps the comparison view payload; result sets above this stay governed by the row rules. */
    private static final int COMPARISON_ROW_LIMIT = 200;

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
        if (exercise.exerciseType() == ExerciseType.QUERY) {
            return evaluateQuery(exercise, dataset, submittedSql, started);
        }
        return evaluateStateful(exercise, dataset, submittedSql, started);
    }

    private ExerciseEvaluationResult evaluateQuery(
        ExerciseDefinition exercise,
        ExerciseDataset dataset,
        String submittedSql,
        long started
    ) {
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
            QueryResult expected;
            try {
                initializeDataset(databasePath, dataset);
                expected = executeQuery(databasePath, exercise.referenceSql());
            } catch (SQLException | SqlTeacherException error) {
                // Evaluation environment or reference answer failure is a content problem,
                // never the student's fault.
                log.warn("Reference SQL failed for exercise {}", exercise.id(), error);
                return failure(
                    started,
                    "REFERENCE_SQL_FAILED",
                    new EvaluationCriterionResult(
                        "reference", false, "题目参考答案未能执行，题目数据可能存在异常，请联系教师或管理员。"
                    )
                );
            }
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
            return finish(started, exercise, criteria, expected, actual);
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

    /**
     * STATE/SCRIPT/TRIGGER grading: the reference answer and the student submission run on
     * two databases initialized from the same setup script, the teacher probe (TRIGGER)
     * produces the comparable state, and the verification query compares both final
     * states. The submission gate is identical to the one enforced in practice sessions.
     */
    private ExerciseEvaluationResult evaluateStateful(
        ExerciseDefinition exercise,
        ExerciseDataset dataset,
        String submittedSql,
        long started
    ) {
        ExerciseSubmissionGate.Decision decision = new ExerciseSubmissionGate(riskAnalysisService)
            .check(exercise.exerciseType(), exercise.effectiveAllowedStatementTypes(), submittedSql);
        if (!decision.allowed()) {
            return failure(
                started,
                "SQL_SAFETY_REJECTED",
                new EvaluationCriterionResult("safety", false, decision.reason())
            );
        }

            Path referencePath = null;
            Path studentPath = null;
            try {
                Files.createDirectories(evaluationDirectory);
                referencePath = Files.createTempFile(evaluationDirectory, "evaluation-", ".db");
                QueryResult expected;
                try {
                    initializeDataset(referencePath, dataset);
                    if (!executeScript(referencePath, SqlScriptSplitter.split(exercise.referenceSql())).success()) {
                        return referenceFailure(started);
                    }
                    if (exercise.exerciseType() == ExerciseType.TRIGGER
                        && !executeScript(referencePath, SqlScriptSplitter.split(exercise.triggerProbeSql())).success()) {
                        return referenceFailure(started);
                    }
                    expected = executeQuery(referencePath, exercise.verificationSql());
                } catch (SQLException | SqlTeacherException error) {
                    log.warn("Reference script failed for exercise {}", exercise.id(), error);
                    return referenceFailure(started);
                }

            studentPath = Files.createTempFile(evaluationDirectory, "evaluation-", ".db");
            initializeDataset(studentPath, dataset);
            ScriptOutcome outcome = executeScript(studentPath, decision.statements());
            if (!outcome.success()) {
                return failure(
                    started,
                    "SQL_EXECUTION_FAILED",
                    new EvaluationCriterionResult(
                        "execution", false,
                        "第 " + outcome.failedIndex() + " 条语句未能执行，请检查语法、表名和字段名。"
                    )
                );
            }
            if (exercise.exerciseType() == ExerciseType.TRIGGER) {
                ScriptOutcome probeOutcome = executeScript(studentPath, SqlScriptSplitter.split(exercise.triggerProbeSql()));
                if (!probeOutcome.success()) {
                    return failure(
                        started,
                        "RESULT_MISMATCH",
                        new EvaluationCriterionResult(
                            "verification", false,
                            "触发场景验证未能完成，你的触发器执行后数据状态与题目预期不一致。"
                        )
                    );
                }
            }
            QueryResult actual;
            try {
                actual = executeQuery(studentPath, exercise.verificationSql());
            } catch (SQLException error) {
                return failure(
                    started,
                    "RESULT_MISMATCH",
                    new EvaluationCriterionResult(
                        "verification", false, "验证查询未能在你的结果上执行，请检查是否破坏了题目要求的表结构。"
                    )
                );
            }
            if (expected.truncated() || actual.truncated()) {
                return failure(
                    started,
                    "RESULT_LIMIT_EXCEEDED",
                    new EvaluationCriterionResult("result_limit", false, "结果规模超过评测上限，请联系教师调整题目。")
                );
            }
            String studentText = String.join(";\n", decision.statements());
            List<EvaluationCriterionResult> criteria = new ArrayList<>(evaluateCriteria(
                exercise.evaluationRule(), studentText, expected, actual
            ));
            if (!exercise.evaluationRule().planKeywords().isEmpty()) {
                criteria.add(planKeywordCriterion(exercise.evaluationRule().planKeywords(), actual));
            }
            if (exercise.expectedAffectedRows() != null) {
                boolean affected = outcome.affectedRows() == exercise.expectedAffectedRows();
                criteria.add(new EvaluationCriterionResult(
                    "affected_rows", affected,
                    affected ? "影响行数满足要求。" : "影响行数不符合题目要求：实际影响 " + outcome.affectedRows() + " 行。"
                ));
            }
            if (exercise.exerciseType() == ExerciseType.SCRIPT
                && !exercise.requiredTransactionKeywords().isEmpty()) {
                String normalizedScript = SqlStructureMatcher.normalize(studentText);
                List<String> missing = exercise.requiredTransactionKeywords().stream()
                    .filter(keyword -> !SqlStructureMatcher.containsKeyword(normalizedScript, keyword))
                    .toList();
                criteria.add(new EvaluationCriterionResult(
                    "transaction", missing.isEmpty(),
                    missing.isEmpty() ? "事务结构满足要求。" : "脚本尚未使用题目要求的事务关键字。"
                ));
            }
            return finish(started, exercise, criteria, expected, actual);
        } catch (SQLException error) {
            return failure(
                started,
                "EVALUATION_ENVIRONMENT_FAILED",
                new EvaluationCriterionResult("environment", false, "暂时无法创建评测环境，请稍后重试。")
            );
        } catch (IOException error) {
            return failure(
                started,
                "EVALUATION_ENVIRONMENT_FAILED",
                new EvaluationCriterionResult("environment", false, "暂时无法创建评测环境，请稍后重试。")
            );
        } finally {
            deleteQuietly(referencePath);
            deleteQuietly(studentPath);
        }
    }

    private static ExerciseEvaluationResult referenceFailure(long started) {
        return failure(
            started,
            "REFERENCE_SQL_FAILED",
            new EvaluationCriterionResult(
                "reference", false, "题目参考答案未能执行，题目数据可能存在异常，请联系教师或管理员。"
            )
        );
    }

    /**
     * Runs statements in order on one database with a shared bounded time budget. A
     * statement-level SQL failure is reported in the outcome (with its 1-based position)
     * so it can be attributed to the submission; connection-level failures still throw.
     */
    private static ScriptOutcome executeScript(Path databasePath, List<String> statements) throws SQLException {
        long deadline = System.nanoTime() + QUERY_TIMEOUT_SECONDS * 1_000_000_000L;
        int affectedRows = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            for (int index = 0; index < statements.size(); index++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new ScriptOutcome(false, index + 1, affectedRows);
                }
                statement.setQueryTimeout(Math.max(1, (int) (remaining / 1_000_000_000L)));
                try {
                    boolean hasResult = statement.execute(statements.get(index));
                    if (!hasResult) {
                        affectedRows += Math.max(0, statement.getUpdateCount());
                    }
                } catch (SQLException error) {
                    return new ScriptOutcome(false, index + 1, affectedRows);
                }
            }
        }
        return new ScriptOutcome(true, 0, affectedRows);
    }

    /** Deterministic 0-100 display score plus the reveal-controlled comparison view. */
    private static ExerciseEvaluationResult finish(
        long started,
        ExerciseDefinition exercise,
        List<EvaluationCriterionResult> criteria,
        QueryResult expected,
        QueryResult actual
    ) {
        boolean passed = criteria.stream().allMatch(EvaluationCriterionResult::passed);
        Integer score = computeScore(exercise, criteria);
        ResultComparison comparison = buildComparison(exercise, passed, expected, actual);
        return new ExerciseEvaluationResult(
            passed,
            criteria,
            passed ? "提交通过，结果满足题目要求。" : "提交未通过，请根据分项反馈调整后重试。",
            elapsed(started),
            passed ? "" : "RESULT_MISMATCH",
            score,
            comparison
        );
    }

    /**
     * Weighted display score over the exercise's scored criteria only; absent weights
     * weigh 1 and missing criterion results count as failed. Passing still requires every
     * criterion, so the score alone never grants a pass.
     */
    private static Integer computeScore(ExerciseDefinition exercise, List<EvaluationCriterionResult> criteria) {
        Set<String> scored = exercise.scoredCriteria();
        if (scored.isEmpty()) {
            return null;
        }
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        for (EvaluationCriterionResult criterion : criteria) {
            outcomes.putIfAbsent(criterion.criterion(), criterion.passed());
        }
        long total = 0;
        long earned = 0;
        for (String criterion : scored) {
            int weight = exercise.evaluationRule().weightOf(criterion);
            total += weight;
            if (Boolean.TRUE.equals(outcomes.get(criterion))) {
                earned += weight;
            }
        }
        return total == 0 ? null : (int) Math.round(100.0 * earned / total);
    }

    /** Never leaks expected rows before a submission; NEVER never leaks them at all. */
    private static ResultComparison buildComparison(
        ExerciseDefinition exercise,
        boolean passed,
        QueryResult expected,
        QueryResult actual
    ) {
        ExerciseRevealMode mode = exercise.revealMode();
        if (mode == ExerciseRevealMode.NEVER || (passed && mode != ExerciseRevealMode.ALWAYS)) {
            return null;
        }
        return compareResults(expected, actual, COMPARISON_ROW_LIMIT);
    }

    /**
     * Pairs expected and actual rows deterministically: each expected row takes the first
     * unused actual row with the smallest per-cell difference count. Paired cells are
     * marked where they differ; unpaired rows are marked entirely.
     */
    static ResultComparison compareResults(QueryResult expected, QueryResult actual, int rowLimit) {
        List<String> columns = expected.columns().isEmpty() ? actual.columns() : expected.columns();
        List<List<Object>> expectedRows = expected.rows();
        List<List<Object>> actualRows = actual.rows();
        int expectedCapped = Math.min(expectedRows.size(), rowLimit);
        int actualCapped = Math.min(actualRows.size(), rowLimit);
        // One uniform cell width keeps every cells/cellDiff pair aligned even when the
        // two result sets disagree on column count.
        int width = columns.size();
        for (int index = 0; index < expectedCapped; index++) {
            width = Math.max(width, expectedRows.get(index).size());
        }
        for (int index = 0; index < actualCapped; index++) {
            width = Math.max(width, actualRows.get(index).size());
        }

        boolean[] usedActual = new boolean[actualCapped];
        List<List<Boolean>> actualDiffs = new ArrayList<>(actualCapped);
        for (int index = 0; index < actualCapped; index++) {
            actualDiffs.add(null);
        }
        List<ResultComparison.ComparisonRow> expectedOut = new ArrayList<>(expectedCapped);
        for (int index = 0; index < expectedCapped; index++) {
            List<Object> cells = normalizeCells(expectedRows.get(index), width);
            int bestActual = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int candidate = 0; candidate < actualCapped; candidate++) {
                if (usedActual[candidate]) {
                    continue;
                }
                long distance = rowDistance(cells, normalizeCells(actualRows.get(candidate), width));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestActual = candidate;
                }
            }
            List<Boolean> diff;
            if (bestActual < 0) {
                diff = allTrue(width);
            } else {
                usedActual[bestActual] = true;
                diff = cellDiff(cells, normalizeCells(actualRows.get(bestActual), width));
                actualDiffs.set(bestActual, diff);
            }
            expectedOut.add(new ResultComparison.ComparisonRow(cells, diff));
        }
        List<ResultComparison.ComparisonRow> actualOut = new ArrayList<>(actualCapped);
        for (int index = 0; index < actualCapped; index++) {
            List<Object> cells = normalizeCells(actualRows.get(index), width);
            List<Boolean> diff = actualDiffs.get(index);
            actualOut.add(new ResultComparison.ComparisonRow(cells, diff != null ? diff : allTrue(width)));
        }
        return new ResultComparison(columns, expectedOut, actualOut);
    }

    private static long rowDistance(List<Object> expectedRow, List<Object> actualRow) {
        int shared = Math.min(expectedRow.size(), actualRow.size());
        long distance = Math.abs(expectedRow.size() - actualRow.size());
        for (int index = 0; index < shared; index++) {
            if (!Objects.equals(expectedRow.get(index), actualRow.get(index))) {
                distance++;
            }
        }
        return distance;
    }

    private static List<Boolean> cellDiff(List<Object> expectedRow, List<Object> actualRow) {
        int size = Math.max(expectedRow.size(), actualRow.size());
        List<Boolean> diff = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            Object expectedCell = index < expectedRow.size() ? expectedRow.get(index) : null;
            Object actualCell = index < actualRow.size() ? actualRow.get(index) : null;
            diff.add(!Objects.equals(expectedCell, actualCell));
        }
        return List.copyOf(diff);
    }

    /** Pads the row with null cells up to the uniform comparison width. */
    private static List<Object> normalizeCells(List<Object> row, int width) {
        List<Object> cells = new ArrayList<>(Math.max(row.size(), width));
        cells.addAll(row);
        for (int index = row.size(); index < width; index++) {
            cells.add(null);
        }
        return Collections.unmodifiableList(cells);
    }

    private static List<Boolean> allTrue(int size) {
        List<Boolean> diff = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            diff.add(true);
        }
        return List.copyOf(diff);
    }

    private static EvaluationCriterionResult planKeywordCriterion(List<String> planKeywords, QueryResult planOutput) {
        StringBuilder text = new StringBuilder();
        for (List<Object> row : planOutput.rows()) {
            for (Object cell : row) {
                text.append(cell == null ? "" : String.valueOf(cell)).append(' ');
            }
        }
        String normalized = SqlStructureMatcher.normalize(text.toString());
        List<String> missing = planKeywords.stream()
            .filter(keyword -> !SqlStructureMatcher.containsKeyword(normalized, keyword))
            .toList();
        return new EvaluationCriterionResult(
            "plan_keywords", missing.isEmpty(),
            missing.isEmpty() ? "执行计划满足要求。" : "执行计划尚未满足题目要求：未出现 " + String.join("、", missing) + "。"
        );
    }

    private record ScriptOutcome(boolean success, int failedIndex, int affectedRows) {
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
            String feedback = passed
                ? "结果列满足要求。"
                : "结果列的数量、名称或顺序不符合要求。期望列：" + String.join("、", expected.columns())
                    + "；实际列：" + String.join("、", actual.columns()) + "。";
            criteria.add(new EvaluationCriterionResult("columns", passed, feedback));
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
            // 归一化（注释/字面量屏蔽 + 空白折叠）只做一次，避免按关键字数重复扫描整段 SQL。
            String normalizedSql = SqlStructureMatcher.normalize(submittedSql);
            List<String> missing = rule.requiredSqlKeywords().stream()
                .filter(keyword -> !SqlStructureMatcher.containsKeyword(normalizedSql, keyword))
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
        ExerciseDatasetSqlPolicy.validate(dataset.setupSql());
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
