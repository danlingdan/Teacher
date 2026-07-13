package com.sqlteacher.application.event;

import com.sqlteacher.application.risk.SqlRiskLevel;

import java.time.Duration;

/**
 * Records P0 learning events without accepting raw SQL, natural-language prompts, or model output.
 */
public interface LearningEventService {
    void recordSqlExecution(
        String connectionId,
        boolean successful,
        String statementType,
        Duration duration,
        int resultCount,
        String errorCode
    );

    void recordSqlRiskBlocked(
        String connectionId,
        String statementType,
        SqlRiskLevel riskLevel,
        boolean multiStatement
    );

    void recordAiGeneration(
        String connectionId,
        boolean successful,
        String model,
        String promptVersion,
        String errorCode
    );
}
