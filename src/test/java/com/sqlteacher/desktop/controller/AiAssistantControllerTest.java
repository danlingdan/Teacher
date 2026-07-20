package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyResult;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldRejectUnsafeOrMissingDrafts() {
        Nl2SqlSafetyResult deleteResult = result(
            "DELETE FROM student",
            new SqlRiskAnalysis(SqlRiskLevel.MEDIUM, true, true, false, "DELETE", List.of("Modifies data."))
        );

        assertFalse(AiAssistantController.canCopyDraft(deleteResult, "DELETE FROM student"));
        assertFalse(AiAssistantController.canCopyDraft(null, "SELECT 1"));
        assertFalse(AiAssistantController.canCopyDraft(deleteResult, null));
    }

    private static Nl2SqlSafetyResult result(String sql, SqlRiskAnalysis risk) {
        return new Nl2SqlSafetyResult(
            new Nl2SqlPlan(sql, "QUERY", "test explanation", "test-model", "test-v1"),
            risk
        );
    }
}
