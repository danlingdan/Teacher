package com.sqlteacher.application.event;

import com.sqlteacher.application.risk.SqlRiskLevel;
import com.sqlteacher.domain.activity.ActivityType;

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

    default void recordExerciseAttempt(
        String exerciseId,
        String status,
        boolean successful,
        Duration duration,
        String errorCode
    ) {
    }

    default void recordActivityEvaluation(
        String activityId,
        ActivityType activityType,
        String status,
        boolean successful,
        Duration duration,
        String evaluatorVersion,
        String evidenceVersion,
        String reasonCode
    ) {
    }

    /** v3.3 W6.1: recorded when a confirmation token is issued for a risky statement. */
    default void recordSqlConfirmationIssued(String connectionId, String statementType) {
    }

    /** v3.3 W6.1: recorded when a confirmation token is consumed by an execution. */
    default void recordSqlConfirmed(String connectionId, String statementType) {
    }

    /** v3.3 W6.1: recorded when a token is cancelled, expired, or fails validation. */
    default void recordSqlConfirmationCancelled(String connectionId, String reason) {
    }

    default void recordExerciseHint(String exerciseId, int hintLevel) {
    }

    default void recordKnowledgeSearch(int queryLength, int resultCount) {
    }
}
