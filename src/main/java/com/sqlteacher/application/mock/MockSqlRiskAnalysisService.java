package com.sqlteacher.application.mock;

import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;

import java.util.List;
import java.util.Locale;

public final class MockSqlRiskAnalysisService implements SqlRiskAnalysisService {
    @Override
    public SqlRiskAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return blocked("UNKNOWN", false, "SQL must not be blank");
        }

        String normalized = sql.strip();
        boolean multiStatement = hasMultipleStatements(normalized);
        String statementType = firstKeyword(normalized);
        if (multiStatement) {
            return blocked(statementType, true, "The UI mock blocks multiple SQL statements");
        }
        if (!"SELECT".equals(statementType)) {
            return blocked(statementType, false, "The UI mock only permits SELECT statements");
        }
        return new SqlRiskAnalysis(
            SqlRiskLevel.LOW,
            true,
            false,
            false,
            statementType,
            List.of("Read-only mock query")
        );
    }

    private static SqlRiskAnalysis blocked(String statementType, boolean multiStatement, String reason) {
        return new SqlRiskAnalysis(
            SqlRiskLevel.FORBIDDEN,
            false,
            false,
            multiStatement,
            statementType,
            List.of(reason)
        );
    }

    private static boolean hasMultipleStatements(String sql) {
        String withoutTrailingTerminator = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        return withoutTrailingTerminator.contains(";");
    }

    private static String firstKeyword(String sql) {
        int end = 0;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return end == 0 ? "UNKNOWN" : sql.substring(0, end).toUpperCase(Locale.ROOT);
    }
}
