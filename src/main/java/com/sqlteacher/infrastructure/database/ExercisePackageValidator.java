package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Deterministic self-test for exercise packages: dataset SQL must execute on an in-memory
 * database and every reference SQL must run against its dataset and satisfy its own
 * evaluation rule. Validation never persists anything; importers use the result to reject
 * broken packages before they are stored.
 */
final class ExercisePackageValidator {
    private static final int MAX_SELF_TEST_ROWS = 5_000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final SqlRiskAnalysisService riskAnalysisService;

    ExercisePackageValidator(SqlRiskAnalysisService riskAnalysisService) {
        this.riskAnalysisService = riskAnalysisService;
    }

    record ItemStatus(String id, boolean passed, String message) {
    }

    record Result(List<ItemStatus> datasets, List<ItemStatus> exercises) {
        Result {
            datasets = List.copyOf(datasets);
            exercises = List.copyOf(exercises);
        }

        boolean passed() {
            return datasets.stream().allMatch(ItemStatus::passed)
                && exercises.stream().allMatch(ItemStatus::passed);
        }

        List<String> failures() {
            List<String> failures = new ArrayList<>();
            datasets.stream().filter(status -> !status.passed())
                .forEach(status -> failures.add("数据集 " + status.id() + "：" + status.message()));
            exercises.stream().filter(status -> !status.passed())
                .forEach(status -> failures.add("题目 " + status.id() + "：" + status.message()));
            return List.copyOf(failures);
        }
    }

    /**
     * @param storedDatasets resolves datasets that already exist in the database and are
     *                       referenced (but not redefined) by the package
     */
    Result validate(
        List<ExerciseDataset> packageDatasets,
        List<ExerciseDefinition> exercises,
        Function<String, Optional<ExerciseDataset>> storedDatasets
    ) {
        Map<String, ExerciseDataset> datasetById = new LinkedHashMap<>();
        for (ExerciseDataset dataset : packageDatasets) {
            datasetById.putIfAbsent(dataset.id(), dataset);
        }
        for (ExerciseDefinition exercise : exercises) {
            if (!datasetById.containsKey(exercise.datasetId())) {
                storedDatasets.apply(exercise.datasetId()).ifPresent(dataset -> datasetById.put(dataset.id(), dataset));
            }
        }

        Map<String, Connection> selfTestDatabases = new LinkedHashMap<>();
        List<ItemStatus> datasetStatuses = new ArrayList<>();
        List<ItemStatus> exerciseStatuses = new ArrayList<>();
        try {
            Set<String> packageDatasetIds = packageDatasets.stream()
                .map(ExerciseDataset::id)
                .collect(java.util.stream.Collectors.toSet());
            for (ExerciseDataset dataset : datasetById.values()) {
                ItemStatus status = validateDataset(dataset, selfTestDatabases);
                if (packageDatasetIds.contains(dataset.id()) || !status.passed()) {
                    datasetStatuses.add(status);
                }
            }
            for (ExerciseDefinition exercise : exercises) {
                exerciseStatuses.add(validateExercise(exercise, datasetById, selfTestDatabases));
            }
        } finally {
            selfTestDatabases.values().forEach(ExercisePackageValidator::closeQuietly);
        }
        return new Result(datasetStatuses, exerciseStatuses);
    }

    private ItemStatus validateDataset(ExerciseDataset dataset, Map<String, Connection> selfTestDatabases) {
        try {
            ExerciseDatasetSqlPolicy.validate(dataset.setupSql());
            SqliteDriver.ensureLoaded();
        } catch (SqlTeacherException | SQLException error) {
            return new ItemStatus(
                dataset.id(), false,
                error instanceof SqlTeacherException policyError ? policyError.getMessage() : "数据集自测环境创建失败"
            );
        }
        Connection connection = null;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
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
            selfTestDatabases.put(dataset.id(), connection);
            return new ItemStatus(dataset.id(), true, "数据集 SQL 自测通过");
        } catch (SQLException | RuntimeException error) {
            closeQuietly(connection);
            return new ItemStatus(dataset.id(), false, "数据集 SQL 未能执行成功");
        }
    }

    private ItemStatus validateExercise(
        ExerciseDefinition exercise,
        Map<String, ExerciseDataset> datasetById,
        Map<String, Connection> selfTestDatabases
    ) {
        if (!datasetById.containsKey(exercise.datasetId())) {
            return new ItemStatus(exercise.id(), false, "引用的数据集不存在：" + exercise.datasetId());
        }
        SqlRiskAnalysis risk = riskAnalysisService.analyze(exercise.referenceSql(), DatabaseDialect.SQLITE);
        if (!risk.executable() || risk.multiStatement() || !"SELECT".equals(risk.statementType())) {
            return new ItemStatus(exercise.id(), false, "参考答案必须是单条只读 SELECT 查询");
        }
        Connection connection = selfTestDatabases.get(exercise.datasetId());
        if (connection == null) {
            return new ItemStatus(exercise.id(), false, "数据集未能建立自测环境");
        }
        try (Statement settings = connection.createStatement()) {
            settings.execute("pragma query_only = on");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_SELF_TEST_ROWS + 1);
                int rowCount = 0;
                try (ResultSet rows = statement.executeQuery(exercise.referenceSql())) {
                    while (rows.next()) {
                        rowCount++;
                        if (rowCount > MAX_SELF_TEST_ROWS) {
                            break;
                        }
                    }
                }
                return checkRule(exercise.evaluationRule(), rowCount, exercise);
            }
        } catch (SQLException error) {
            return new ItemStatus(exercise.id(), false, "参考答案未能在数据集上执行");
        }
    }

    private ItemStatus checkRule(ExerciseEvaluationRule rule, int rowCount, ExerciseDefinition exercise) {
        if (rule.expectedRowCount() != null && rule.expectedRowCount() != rowCount) {
            return new ItemStatus(
                exercise.id(), false,
                "参考答案返回 " + rowCount + " 行，与评测规则期望的 " + rule.expectedRowCount() + " 行不一致"
            );
        }
        if (!rule.requiredSqlKeywords().isEmpty()) {
            String normalized = SqlStructureMatcher.normalize(exercise.referenceSql());
            List<String> missing = rule.requiredSqlKeywords().stream()
                .filter(keyword -> !SqlStructureMatcher.containsKeyword(normalized, keyword))
                .toList();
            if (!missing.isEmpty()) {
                return new ItemStatus(
                    exercise.id(), false,
                    "参考答案未使用题目要求的结构关键字：" + String.join("、", missing)
                );
            }
        }
        return new ItemStatus(exercise.id(), true, "参考答案自测通过");
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Self-test cleanup only; the original validation result is already recorded.
        }
    }
}
