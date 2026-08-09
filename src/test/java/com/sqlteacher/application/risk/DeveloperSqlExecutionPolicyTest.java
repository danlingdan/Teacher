package com.sqlteacher.application.risk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeveloperSqlExecutionPolicyTest {
    @Test
    void shouldRemoveRoutinePromptsButKeepDestructiveConfirmation() {
        SqlRiskAnalysis insert = analysis(SqlRiskLevel.MEDIUM, true, true, false, "INSERT");
        SqlRiskAnalysis create = analysis(SqlRiskLevel.HIGH, true, true, false, "CREATE");
        SqlRiskAnalysis drop = analysis(SqlRiskLevel.FORBIDDEN, false, false, false, "DROP");

        assertFalse(DeveloperSqlExecutionPolicy.apply(insert, true).confirmationRequired());
        assertFalse(DeveloperSqlExecutionPolicy.apply(create, true).confirmationRequired());
        SqlRiskAnalysis adjustedDrop = DeveloperSqlExecutionPolicy.apply(drop, true);
        assertTrue(adjustedDrop.executable());
        assertTrue(adjustedDrop.confirmationRequired());
    }

    @Test
    void shouldNeverRelaxMultiStatementOrAdministrativeSql() {
        SqlRiskAnalysis multiple = analysis(SqlRiskLevel.HIGH, false, true, true, "SELECT");
        SqlRiskAnalysis admin = analysis(SqlRiskLevel.FORBIDDEN, false, false, false, "DROP_ADMIN");
        assertFalse(DeveloperSqlExecutionPolicy.apply(multiple, true).executable());
        assertFalse(DeveloperSqlExecutionPolicy.apply(admin, true).executable());
    }

    private static SqlRiskAnalysis analysis(SqlRiskLevel level, boolean executable,
                                            boolean confirmation, boolean multiple, String type) {
        return new SqlRiskAnalysis(level, executable, confirmation, multiple, type, List.of("test"));
    }
}
