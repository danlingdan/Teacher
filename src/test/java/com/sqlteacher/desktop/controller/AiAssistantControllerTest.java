package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyResult;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAssistantControllerTest {

    @Test
    void shouldAllowOnlyTheAcceptedDisplayedDraftToBeCopied() {
        Nl2SqlSafetyResult result = result(
            "SELECT name FROM student LIMIT 500",
            new SqlRiskAnalysis(SqlRiskLevel.LOW, true, false, false, "SELECT", List.of("Read-only query."))
        );

        assertTrue(AiAssistantController.canCopyDraft(result, "SELECT name FROM student LIMIT 500"));
        assertFalse(AiAssistantController.canCopyDraft(result, "SELECT * FROM class"));
    }

    @Test
    void shouldAllowRiskyExecutableDraftForReviewButRejectForbiddenOrMissingDrafts() {
        Nl2SqlSafetyResult updateResult = result(
            "UPDATE student SET score = 90 WHERE id = 1",
            new SqlRiskAnalysis(SqlRiskLevel.HIGH, true, true, false, "UPDATE", List.of("Modifies data."))
        );
        Nl2SqlSafetyResult dropResult = result(
            "DROP TABLE student",
            new SqlRiskAnalysis(SqlRiskLevel.FORBIDDEN, false, false, false, "DROP", List.of("Forbidden."))
        );

        assertTrue(AiAssistantController.canCopyDraft(updateResult, "UPDATE student SET score = 90 WHERE id = 1"));
        assertFalse(AiAssistantController.canCopyDraft(dropResult, "DROP TABLE student"));
        assertFalse(AiAssistantController.canCopyDraft(null, "SELECT 1"));
        assertFalse(AiAssistantController.canCopyDraft(updateResult, null));
    }

    @Test
    void shouldExposeTheActualProviderFailureInsteadOfClaimingAllFailuresAreOffline() {
        Nl2SqlSafetyResult failed = new Nl2SqlSafetyResult(
            new Nl2SqlPlan("", "", "Network AI request failed (HTTP 404)", "deepseek", "v3"),
            new SqlRiskAnalysis(SqlRiskLevel.FORBIDDEN, false, false, false, "UNKNOWN", List.of("No SQL draft."))
        );

        assertEquals("Network AI request failed (HTTP 404)", AiAssistantController.failureMessage(failed));
        assertEquals("AI 请求未完成，请检查 Provider 配置后重试", AiAssistantController.failureMessage(null));
    }

    private static Nl2SqlSafetyResult result(String sql, SqlRiskAnalysis risk) {
        return new Nl2SqlSafetyResult(
            new Nl2SqlPlan(sql, "QUERY", "test explanation", "test-model", "test-v1"),
            risk
        );
    }
}
