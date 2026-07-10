package com.sqlteacher.application.mock;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockApplicationServicesTest {
    private final MockSqlRiskAnalysisService riskService = new MockSqlRiskAnalysisService();

    @Test
    void shouldProvideUiReadySelectResult() {
        MockSqlExecutionService service = new MockSqlExecutionService(riskService);

        SqlExecutionResult result = service.execute(new SqlExecutionRequest(
            "demo",
            "SELECT id, name, score FROM student",
            1,
            Duration.ofSeconds(2)
        ));

        assertTrue(result.success());
        assertEquals(3, result.columns().size());
        assertEquals(1, result.rows().size());
        assertTrue(result.truncated());
    }

    @Test
    void shouldBlockUnsafeStatementsInUiMock() {
        SqlRiskAnalysis risk = riskService.analyze("DROP TABLE student");

        assertFalse(risk.executable());
        assertThrows(SqlTeacherException.class, () -> new MockSqlExecutionService(riskService).execute(
            new SqlExecutionRequest("demo", "DROP TABLE student", 100, Duration.ofSeconds(2))
        ));
    }

    @Test
    void shouldRejectBlankSqlAsInvalidRequest() {
        MockSqlExecutionService service = new MockSqlExecutionService(riskService);

        assertThrows(IllegalArgumentException.class, () -> service.execute(
            new SqlExecutionRequest("demo", " ", 100, Duration.ofSeconds(2))
        ));
        assertThrows(IllegalArgumentException.class, () -> service.execute(
            new SqlExecutionRequest("demo", null, 100, Duration.ofSeconds(2))
        ));
    }

    @Test
    void shouldReturnNl2SqlDraftWithoutExecutingIt() {
        var plan = new MockNl2SqlService().generate(new Nl2SqlRequest("List students", "demo"));

        assertTrue(plan.sqlDraft().startsWith("SELECT"));
        assertEquals("mock-v1", plan.promptVersion());
    }
}
