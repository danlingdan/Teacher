package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSqlRiskAnalysisServiceTest {

    private final DefaultSqlRiskAnalysisService service =
            new DefaultSqlRiskAnalysisService();

    @Test
    void shouldAllowSelect() {

        SqlRiskAnalysis result =
                service.analyze("SELECT * FROM student");

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.LOW, result.level());
        assertFalse(result.confirmationRequired());
        assertEquals("SELECT", result.statementType());
    }

    @Test
    void shouldRequireConfirmationForUpdate() {

        SqlRiskAnalysis result =
                service.analyze("UPDATE student SET score=100");

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.MEDIUM, result.level());
        assertTrue(result.confirmationRequired());
        assertEquals("UPDATE", result.statementType());
    }

    @Test
    void shouldBlockDropTable() {

        SqlRiskAnalysis result =
                service.analyze("DROP TABLE student");

        assertFalse(result.executable());
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
    }

    @Test
    void shouldBlockMultipleStatements() {

        SqlRiskAnalysis result =
                service.analyze("SELECT * FROM student;DELETE FROM student");

        assertFalse(result.executable());
        assertTrue(result.multiStatement());
    }

    @Test
    void shouldRejectBlankSql() {

        SqlRiskAnalysis result =
                service.analyze(" ");

        assertFalse(result.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, result.level());
    }

}