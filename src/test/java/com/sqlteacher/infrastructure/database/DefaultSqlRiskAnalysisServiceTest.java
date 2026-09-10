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
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
        assertEquals("UPDATE", result.statementType());
    }

    @Test
    void shouldRequireConfirmationForDropTable() {

        SqlRiskAnalysis result =
                service.analyze("DROP TABLE student");

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
    }

    @Test
    void shouldRequireConfirmationForTruncateAndDelete() {
        SqlRiskAnalysis truncate = service.analyze("TRUNCATE TABLE student");
        SqlRiskAnalysis delete = service.analyze("DELETE FROM student WHERE id = 1");

        assertTrue(truncate.executable());
        assertEquals(SqlRiskLevel.HIGH, truncate.level());
        assertTrue(truncate.confirmationRequired());
        assertTrue(delete.executable());
        assertEquals(SqlRiskLevel.HIGH, delete.level());
        assertTrue(delete.confirmationRequired());
    }

    @Test
    void shouldRequireConfirmationWithBackupWarningForDropDatabase() {
        SqlRiskAnalysis result = service.analyze("DROP DATABASE school");

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
        assertEquals(2, result.reasons().size());
        assertTrue(result.reasons().get(1).contains("backup"));
    }

    @Test
    void shouldNotLetCommentsHideDropDatabase() {
        SqlRiskAnalysis blockCommentResult = service.analyze(
                "/* generated SQL */ DROP /* target kind */ DATABASE school");
        SqlRiskAnalysis lineCommentResult = service.analyze(
                "-- generated SQL\nDROP -- target kind\nDATABASE school");

        for (SqlRiskAnalysis result : java.util.List.of(blockCommentResult, lineCommentResult)) {
            assertTrue(result.executable());
            assertEquals(SqlRiskLevel.HIGH, result.level());
            assertTrue(result.confirmationRequired());
        }
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
    void shouldKeepUserAndRoleAdministrationForbidden() {
        for (String sql : java.util.List.of(
            "CREATE USER student IDENTIFIED BY 'secret'",
            "ALTER USER student IDENTIFIED BY 'changed'",
            "DROP ROLE teacher",
            "DROP USER student"
        )) {
            SqlRiskAnalysis result = service.analyze(sql);
            assertFalse(result.executable(), sql);
            assertEquals(SqlRiskLevel.FORBIDDEN, result.level(), sql);
        }
    }

    @Test
    void shouldRequireConfirmationForPrivilegeAndSchemaLevelStatements() {
        for (String sql : java.util.List.of(
            "GRANT SELECT ON school.* TO student",
            "REVOKE ALL ON school.* FROM student",
            "DROP SCHEMA school"
        )) {
            SqlRiskAnalysis result = service.analyze(sql);
            assertTrue(result.executable(), sql);
            assertEquals(SqlRiskLevel.HIGH, result.level(), sql);
            assertTrue(result.confirmationRequired(), sql);
        }
    }

    @Test
    void shouldBlockMysqlFileOutputLockingAndDelayFunctions() {
        for (String sql : java.util.List.of(
            "SELECT * FROM student INTO OUTFILE '/tmp/students.csv'",
            "SELECT * FROM student FOR UPDATE",
            "SELECT GET_LOCK('lesson', 5)",
            "SELECT SLEEP(10)"
        )) {
            SqlRiskAnalysis result = service.analyze(sql, DatabaseDialect.MYSQL);
            assertFalse(result.executable(), sql);
            assertEquals(SqlRiskLevel.FORBIDDEN, result.level(), sql);
        }
    }

    @Test
    void shouldRequireConfirmationForMysqlFileReadFunction() {
        SqlRiskAnalysis result = service.analyze("SELECT LOAD_FILE('/etc/passwd')", DatabaseDialect.MYSQL);

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
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

    @Test
    void shouldKeepBenignExecutableCommentExecutableOnMysql() {
        SqlRiskAnalysis result = service.analyze(
                "SELECT /*!40001 SQL_NO_CACHE */ * FROM student", DatabaseDialect.MYSQL);

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.LOW, result.level());
        assertEquals("SELECT", result.statementType());
    }

    @Test
    void shouldAnalyzePayloadHiddenInExecutableCommentOnMysql() {
        SqlRiskAnalysis outfile = service.analyze(
                "SELECT * FROM t /*!40001 INTO OUTFILE '/tmp/x' */", DatabaseDialect.MYSQL);
        SqlRiskAnalysis genericOutfile = service.analyze(
                "SELECT * FROM t /*! INTO OUTFILE '/tmp/x' */", DatabaseDialect.GENERIC);

        assertFalse(outfile.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, outfile.level());
        assertFalse(genericOutfile.executable());
    }

    @Test
    void shouldRejectStatementSplitInsideExecutableComment() {
        SqlRiskAnalysis result = service.analyze(
                "SELECT 1 /*!; DROP TABLE t */", DatabaseDialect.MYSQL);

        assertFalse(result.executable());
        assertTrue(result.multiStatement());
    }

    @Test
    void shouldStripExecutableCommentsAsPlainCommentsOnOtherDialects() {
        SqlRiskAnalysis result = service.analyze(
                "SELECT * FROM t /*! INTO OUTFILE '/tmp/x' */", DatabaseDialect.SQLITE);

        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.LOW, result.level());
    }

    @Test
    void shouldAllowCteSelectQueries() {
        SqlRiskAnalysis cte = service.analyze(
                "WITH ranked AS (SELECT id, score FROM student) SELECT * FROM ranked");
        SqlRiskAnalysis recursive = service.analyze(
                "WITH RECURSIVE seq(n) AS (VALUES (1) UNION ALL SELECT n+1 FROM seq WHERE n < 10) "
                        + "SELECT * FROM seq",
                DatabaseDialect.SQLITE);

        assertEquals("SELECT", cte.statementType());
        assertTrue(cte.executable());
        assertEquals(SqlRiskLevel.LOW, cte.level());
        assertTrue(recursive.executable());
    }

    @Test
    void shouldAllowParenthesizedCompoundSelects() {
        SqlRiskAnalysis result = service.analyze("(SELECT 1) UNION (SELECT 2) ORDER BY 1");

        assertEquals("SELECT", result.statementType());
        assertTrue(result.executable());
        assertEquals(SqlRiskLevel.LOW, result.level());
    }

    @Test
    void shouldClassifyCteMutationByItsMainStatement() {
        SqlRiskAnalysis insert = service.analyze(
                "WITH staged AS (SELECT 1 AS id) INSERT INTO student SELECT * FROM staged");
        SqlRiskAnalysis drop = service.analyze("WITH staged AS (SELECT id FROM student) DROP TABLE staged");

        assertEquals("INSERT", insert.statementType());
        assertEquals(SqlRiskLevel.MEDIUM, insert.level());
        assertTrue(insert.confirmationRequired());
        assertEquals("DROP", drop.statementType());
        assertTrue(drop.confirmationRequired());
    }

    @Test
    void shouldFailClosedWhenCteHeaderIsUnparseable() {
        SqlRiskAnalysis result = service.analyze("WITH \"quoted\" AS (SELECT 1) SELECT * FROM quoted");

        assertFalse(result.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, result.level());
    }

    @Test
    void shouldRequireConfirmationForFileReadFunctionsPerDialect() {
        for (SqlCase sqlCase : java.util.List.of(
            new SqlCase(DatabaseDialect.H2, "SELECT FILE_READ('/etc/passwd') FROM dual"),
            new SqlCase(DatabaseDialect.H2, "SELECT * FROM CSVREAD('/tmp/data.csv')"),
            new SqlCase(DatabaseDialect.DUCKDB, "SELECT * FROM read_csv('/tmp/data.csv')"),
            new SqlCase(DatabaseDialect.DUCKDB, "SELECT read_text('/etc/passwd')"),
            new SqlCase(DatabaseDialect.POSTGRESQL, "SELECT pg_read_file('/etc/passwd')"),
            new SqlCase(DatabaseDialect.POSTGRESQL, "SELECT pg_read_binary_file('/etc/passwd')"),
            new SqlCase(DatabaseDialect.GENERIC, "SELECT LOAD_FILE('/etc/passwd')"),
            new SqlCase(DatabaseDialect.GENERIC, "SELECT * FROM read_json('/tmp/data.json')")
        )) {
            SqlRiskAnalysis result = service.analyze(sqlCase.sql(), sqlCase.dialect());
            assertTrue(result.executable(), sqlCase.sql());
            assertEquals(SqlRiskLevel.HIGH, result.level(), sqlCase.sql());
            assertTrue(result.confirmationRequired(), sqlCase.sql());
        }
    }

    @Test
    void shouldForbidCopyToExternalTargetsAndConfirmCopyImports() {
        SqlRiskAnalysis toProgram = service.analyze(
                "COPY student TO PROGRAM 'gzip > /tmp/x.gz'", DatabaseDialect.POSTGRESQL);
        SqlRiskAnalysis toFile = service.analyze(
                "COPY student TO '/tmp/students.csv'", DatabaseDialect.POSTGRESQL);
        SqlRiskAnalysis fromFile = service.analyze(
                "COPY student FROM '/tmp/students.csv'", DatabaseDialect.POSTGRESQL);

        assertFalse(toProgram.executable());
        assertEquals(SqlRiskLevel.FORBIDDEN, toProgram.level());
        assertFalse(toFile.executable());
        assertTrue(fromFile.executable());
        assertEquals(SqlRiskLevel.HIGH, fromFile.level());
        assertTrue(fromFile.confirmationRequired());
    }

    @Test
    void shouldTreatCreateTriggerBodyAsSingleStatement() {
        SqlRiskAnalysis result = service.analyze("""
            create trigger trg after insert on student
            begin
              insert into audit values (new.id);
              update stats set total = total + 1;
            end
            """);

        assertTrue(result.executable(), "trigger body must not count as multiple statements");
        assertEquals("CREATE", result.statementType());
        assertEquals(SqlRiskLevel.HIGH, result.level());
        assertTrue(result.confirmationRequired());
    }

    @Test
    void shouldRejectStatementsAfterTriggerBodyEnd() {
        SqlRiskAnalysis withSemicolon = service.analyze("""
            create trigger trg after insert on student
            begin
              insert into audit values (new.id);
            end;
            delete from student
            """);
        SqlRiskAnalysis withoutSemicolon = service.analyze("""
            create trigger trg after insert on student
            begin
              insert into audit values (new.id);
            end
            drop table student
            """);

        assertFalse(withSemicolon.executable());
        assertFalse(withoutSemicolon.executable());
    }

    private record SqlCase(DatabaseDialect dialect, String sql) {
    }

}
