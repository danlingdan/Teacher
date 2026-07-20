package com.sqlteacher.application.nl2sql;

import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultNl2SqlSafetyServiceTest {

    @Test
    void shouldAcceptReadOnlySelectDraftWithoutExecutingIt() {
        RecordingLearningEventService events = new RecordingLearningEventService();
        Nl2SqlSafetyService service = serviceFor(
            plan("SELECT name FROM student"),
            sql -> analysis(SqlRiskLevel.LOW, true, false, false, "SELECT"),
            events
        );

        Nl2SqlSafetyResult result = service.generateAndAssess(request());

        assertTrue(result.accepted());
        assertEquals("SELECT name FROM student", result.plan().sqlDraft());
        assertTrue(events.blockedEvents.isEmpty());
    }

    @Test
    void shouldPreserveAndMarkModifyingDraftAsUnsafe() {
        RecordingLearningEventService events = new RecordingLearningEventService();
        Nl2SqlSafetyService service = serviceFor(
            plan("UPDATE student SET score = 0"),
            sql -> analysis(SqlRiskLevel.MEDIUM, true, true, false, "UPDATE"),
            events
        );

        Nl2SqlSafetyResult result = service.generateAndAssess(request());

        assertFalse(result.accepted());
        assertTrue(result.draftAvailable());
        assertEquals("UPDATE student SET score = 0", result.plan().sqlDraft());
        assertEquals(List.of("UPDATE:MEDIUM:false"), events.blockedEvents);
    }

    @Test
    void shouldMarkMultiStatementDraftAsUnsafe() {
        RecordingLearningEventService events = new RecordingLearningEventService();
        Nl2SqlSafetyService service = serviceFor(
            plan("SELECT * FROM student; DROP TABLE student"),
            sql -> analysis(SqlRiskLevel.HIGH, false, true, true, "SELECT"),
            events
        );

        Nl2SqlSafetyResult result = service.generateAndAssess(request());

        assertFalse(result.accepted());
        assertTrue(result.riskAnalysis().multiStatement());
        assertEquals(List.of("SELECT:HIGH:true"), events.blockedEvents);
    }

    @Test
    void shouldNotRecordRiskEventWhenGenerationProducedNoDraft() {
        RecordingLearningEventService events = new RecordingLearningEventService();
        Nl2SqlSafetyService service = serviceFor(
            new Nl2SqlPlan("", "", "Ollama unavailable", "model", "v2"),
            sql -> analysis(SqlRiskLevel.FORBIDDEN, false, false, false, "UNKNOWN"),
            events
        );

        Nl2SqlSafetyResult result = service.generateAndAssess(request());

        assertFalse(result.draftAvailable());
        assertFalse(result.accepted());
        assertTrue(events.blockedEvents.isEmpty());
    }

    @Test
    void shouldForwardTheCurrentDatabaseDialectToRiskAnalysis() {
        AtomicReference<DatabaseDialect> analyzedDialect = new AtomicReference<>();
        SqlRiskAnalysisService riskService = new SqlRiskAnalysisService() {
            @Override
            public SqlRiskAnalysis analyze(String sql) {
                throw new AssertionError("Dialect-aware analysis should be used");
            }

            @Override
            public SqlRiskAnalysis analyze(String sql, DatabaseDialect dialect) {
                analyzedDialect.set(dialect);
                return analysis(SqlRiskLevel.LOW, true, false, false, "SELECT");
            }
        };
        Nl2SqlSafetyService service = serviceFor(
            plan("SELECT name FROM student"),
            riskService,
            new RecordingLearningEventService()
        );

        service.generateAndAssess(new Nl2SqlRequest("query", "mysql-demo", DatabaseDialect.MYSQL));

        assertEquals(DatabaseDialect.MYSQL, analyzedDialect.get());
    }

    private static Nl2SqlSafetyService serviceFor(
        Nl2SqlPlan plan,
        SqlRiskAnalysisService riskService,
        LearningEventService eventService
    ) {
        return new DefaultNl2SqlSafetyService(request -> plan, riskService, eventService);
    }

    private static Nl2SqlPlan plan(String sqlDraft) {
        return new Nl2SqlPlan(sqlDraft, "QUERY", "explanation", "model", "v2");
    }

    private static Nl2SqlRequest request() {
        return new Nl2SqlRequest("query", "demo");
    }

    private static SqlRiskAnalysis analysis(
        SqlRiskLevel level,
        boolean executable,
        boolean confirmationRequired,
        boolean multiStatement,
        String statementType
    ) {
        return new SqlRiskAnalysis(
            level,
            executable,
            confirmationRequired,
            multiStatement,
            statementType,
            List.of("test assessment")
        );
    }

    private static final class RecordingLearningEventService implements LearningEventService {
        private final List<String> blockedEvents = new ArrayList<>();

        @Override
        public void recordSqlExecution(
            String connectionId,
            boolean successful,
            String statementType,
            Duration duration,
            int resultCount,
            String errorCode
        ) {
        }

        @Override
        public void recordSqlRiskBlocked(
            String connectionId,
            String statementType,
            SqlRiskLevel riskLevel,
            boolean multiStatement
        ) {
            blockedEvents.add(statementType + ":" + riskLevel + ":" + multiStatement);
        }

        @Override
        public void recordAiGeneration(
            String connectionId,
            boolean successful,
            String model,
            String promptVersion,
            String errorCode
        ) {
        }
    }
}
