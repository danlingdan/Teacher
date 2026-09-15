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
                "INSERT", List.of("开发者模式下 INSERT 免确认写入。"));
            case "CREATE" -> new SqlRiskAnalysis(SqlRiskLevel.MEDIUM, true, false, false,
                "CREATE", List.of("开发者模式下 CREATE 免确认建表。"));
            case "DROP", "TRUNCATE" -> new SqlRiskAnalysis(SqlRiskLevel.HIGH, true, true, false,
                analysis.statementType(), List.of("该破坏性结构操作仍需明确确认。"));
            default -> analysis;
        };
    }
}
