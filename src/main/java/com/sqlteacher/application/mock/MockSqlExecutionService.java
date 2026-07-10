package com.sqlteacher.application.mock;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.SqlTeacherException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MockSqlExecutionService implements SqlExecutionService {
    private static final List<Map<String, Object>> SAMPLE_ROWS = List.of(
        Map.of("id", 1, "name", "Alice", "score", 92),
        Map.of("id", 2, "name", "Bob", "score", 76)
    );

    private final SqlRiskAnalysisService riskAnalysisService;

    public MockSqlExecutionService(SqlRiskAnalysisService riskAnalysisService) {
        this.riskAnalysisService = Objects.requireNonNull(riskAnalysisService);
    }

    @Override
    public SqlExecutionResult execute(SqlExecutionRequest request) {
        validate(request);
        SqlRiskAnalysis risk = riskAnalysisService.analyze(request.sql());
        if (!risk.executable()) {
            throw new SqlTeacherException("MOCK_SQL_BLOCKED", risk.reasons().getFirst());
        }

        List<Map<String, Object>> rows = SAMPLE_ROWS.stream().limit(request.maxRows()).toList();
        return new SqlExecutionResult(
            true,
            List.of("id", "name", "score"),
            rows,
            0,
            rows.size() < SAMPLE_ROWS.size(),
            "Mock query completed",
            Duration.ofMillis(5)
        );
    }

    private static void validate(SqlExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        if (request.maxRows() <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        if (request.timeout() == null || request.timeout().isZero() || request.timeout().isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
