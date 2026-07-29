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
     * Whether the single draft passed the Java safety gate and may be copied for review.
     * This is not execution authorization; risky executable statements still require
     * the execution layer's explicit confirmation.
     */
    public boolean accepted() {
        return draftAvailable()
            && riskAnalysis.executable()
            && !riskAnalysis.multiStatement();
    }
}
