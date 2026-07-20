package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseDialect;
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

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
    }

    @Test
    void shouldForbidDropDatabaseEvenWhenSchemaChangesAreConfirmable() {
        SqlRiskAnalysis result = service.analyze("DROP DATABASE school");

        assertFalse(result.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, result.level());
        assertFalse(result.confirmationRequired());
    }

    @Test
    void shouldForbidDropDatabaseHiddenByComments() {
        SqlRiskAnalysis blockCommentResult = service.analyze(
                "/* generated SQL */ DROP /* target kind */ DATABASE school");
        SqlRiskAnalysis lineCommentResult = service.analyze(
                "-- generated SQL\nDROP -- target kind\nDATABASE school");

        assertEquals(SqlRiskLevel.FORBIDDEN, blockCommentResult.level());
        assertFalse(blockCommentResult.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, lineCommentResult.level());
        assertFalse(lineCommentResult.executable());
    }

    @Test
    void shouldIgnoreCommentsWithoutChangingQuotedText() {
        SqlRiskAnalysis leadingCommentResult = service.analyze("/* lesson */ SELECT '/* not a comment */'");
        SqlRiskAnalysis trailingSemicolonCommentResult = service.analyze("SELECT 1 /* ; DELETE FROM student */");

        assertEquals(SqlRiskLevel.LOW, leadingCommentResult.level());
        assertTrue(leadingCommentResult.executable());
        assertFalse(trailingSemicolonCommentResult.multiStatement());
        assertTrue(trailingSemicolonCommentResult.executable());
    }

    @Test
    void shouldBlockMultipleStatements() {

        SqlRiskAnalysis result =
                service.analyze("SELECT * FROM student;DELETE FROM student");

        assertFalse(result.executable());
        assertTrue(result.multiStatement());
    }

    @Test
    void shouldNotTreatKeywordsOrSemicolonsInsideStringsAsMultipleStatements() {
        SqlRiskAnalysis keywordResult = service.analyze("SELECT 'please DELETE everything'");
        SqlRiskAnalysis semicolonResult = service.analyze("SELECT ';' AS marker");
        SqlRiskAnalysis dropDatabaseTextResult = service.analyze("SELECT 'DROP DATABASE' AS lesson");

        assertTrue(keywordResult.executable());
        assertFalse(keywordResult.multiStatement());
        assertTrue(semicolonResult.executable());
        assertFalse(semicolonResult.multiStatement());
        assertTrue(dropDatabaseTextResult.executable());
    }

    @Test
    void shouldRejectBlankSql() {

        SqlRiskAnalysis result =
                service.analyze(" ");

        assertFalse(result.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, result.level());
    }

    @Test
    void shouldBlockMysqlFileOutputLockingAndDangerousFunctions() {
        for (String sql : java.util.List.of(
            "SELECT * FROM student INTO OUTFILE '/tmp/students.csv'",
            "SELECT * FROM student FOR UPDATE",
            "SELECT GET_LOCK('lesson', 5)",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT SLEEP(10)"
        )) {
            SqlRiskAnalysis result = service.analyze(sql, DatabaseDialect.MYSQL);
            assertFalse(result.executable(), sql);
            assertEquals(SqlRiskLevel.FORBIDDEN, result.level(), sql);
        }
    }

    @Test
    void shouldApplyMysqlRulesToMariaDbWithoutMatchingQuotedTeachingText() {
        SqlRiskAnalysis blocked = service.analyze(
            "SELECT * FROM lesson LOCK IN SHARE MODE",
            DatabaseDialect.MARIADB
        );
        SqlRiskAnalysis quoted = service.analyze(
            "SELECT 'INTO OUTFILE', 'SLEEP(10)', 'FOR UPDATE'",
            DatabaseDialect.MYSQL
        );

        assertFalse(blocked.executable());
        assertTrue(quoted.executable());
    }

}
