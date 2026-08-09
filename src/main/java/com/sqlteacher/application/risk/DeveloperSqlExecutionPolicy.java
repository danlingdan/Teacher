package com.sqlteacher.application.risk;

import java.util.List;
import java.util.Objects;

/** Applies developer-friendly SQL behavior without bypassing non-negotiable boundaries. */
public final class DeveloperSqlExecutionPolicy {
    private DeveloperSqlExecutionPolicy() { }

    public static SqlRiskAnalysis apply(SqlRiskAnalysis analysis, boolean developerMode) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        if (!developerMode || analysis.multiStatement()) return analysis;
        return switch (analysis.statementType()) {
            case "INSERT" -> new SqlRiskAnalysis(SqlRiskLevel.MEDIUM, true, false, false,
                "INSERT", List.of("Data insertion is allowed in developer mode."));
            case "CREATE" -> new SqlRiskAnalysis(SqlRiskLevel.MEDIUM, true, false, false,
                "CREATE", List.of("Schema creation is allowed in developer mode."));
            case "DROP", "TRUNCATE" -> new SqlRiskAnalysis(SqlRiskLevel.HIGH, true, true, false,
                analysis.statementType(), List.of("This destructive schema operation requires explicit confirmation."));
            default -> analysis;
        };
    }
}
