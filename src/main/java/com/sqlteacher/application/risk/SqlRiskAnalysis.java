package com.sqlteacher.application.risk;

import java.util.List;

public record SqlRiskAnalysis(
    SqlRiskLevel level,
    boolean executable,
    boolean confirmationRequired,
    boolean multiStatement,
    String statementType,
    List<String> reasons
) {
    public SqlRiskAnalysis {
        reasons = List.copyOf(reasons);
    }
}
