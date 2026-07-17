package com.sqlteacher.application.mock;

import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.risk.SqlRiskLevel;

import java.time.Duration;

public final class MockLearningEventService implements LearningEventService {
    @Override
    public void recordSqlExecution(String connectionId, boolean successful, String statementType, Duration duration, int resultCount, String errorCode) {
    }

    @Override
    public void recordSqlRiskBlocked(String connectionId, String statementType, SqlRiskLevel riskLevel, boolean multiStatement) {
    }

    @Override
    public void recordAiGeneration(String connectionId, boolean successful, String model, String promptVersion, String errorCode) {
    }
}