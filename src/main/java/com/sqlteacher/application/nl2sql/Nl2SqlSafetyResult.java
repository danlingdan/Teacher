package com.sqlteacher.application.nl2sql;

import com.sqlteacher.application.risk.SqlRiskAnalysis;

import java.util.Objects;

/**
 * AI SQL draft together with the authoritative Java-side risk assessment.
 */
public record Nl2SqlSafetyResult(
    Nl2SqlPlan plan,
    SqlRiskAnalysis riskAnalysis
) {
    public Nl2SqlSafetyResult {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(riskAnalysis, "riskAnalysis must not be null");
    }

    public boolean draftAvailable() {
        return plan.sqlDraft() != null && !plan.sqlDraft().isBlank();
    }

    /**
     * Whether the draft passed the read-only AI safety gate. This is not execution
     * authorization; callers must continue to present the SQL as a draft.
     */
    public boolean accepted() {
        return draftAvailable()
            && riskAnalysis.executable()
            && !riskAnalysis.confirmationRequired()
            && !riskAnalysis.multiStatement()
            && "SELECT".equals(riskAnalysis.statementType());
    }
}
