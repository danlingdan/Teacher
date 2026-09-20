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

    /**
     * v3.7.0 TFB-D2: richer execution evidence (truncated SQL text, dialect, and a SQL hash for
     * correlating AI drafts with executions). Legacy callers keep the lean signature above.
     */
    default void recordSqlExecution(
        String connectionId,
        boolean successful,
        String statementType,
        Duration duration,
        int resultCount,
        String errorCode,
        String sqlText,
        String dialect
    ) {
        recordSqlExecution(connectionId, successful, statementType, duration, resultCount, errorCode);
    }

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

    /** v3.7.0 TFB-D2: AI drafts carry truncated SQL text plus a hash for execution correlation. */
    default void recordAiGeneration(
        String connectionId,
        boolean successful,
        String model,
        String promptVersion,
        String errorCode,
        String generatedSql
    ) {
        recordAiGeneration(connectionId, successful, model, promptVersion, errorCode);
    }

    default void recordExerciseAttempt(
        String exerciseId,
        String status,
        boolean successful,
        Duration duration,
        String errorCode
    ) {
    }

    /** v3.7.0 TFB-D2: submissions carry the score and truncated SQL the student submitted. */
    default void recordExerciseAttempt(
        String exerciseId,
        String status,
        boolean successful,
        Duration duration,
        String errorCode,
        Integer score,
        String sqlText
    ) {
        recordExerciseAttempt(exerciseId, status, successful, duration, errorCode);
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

    /** v3.7.0 TFB-D2: carries a short search preview; the full query never uploads. */
    default void recordKnowledgeSearch(int queryLength, int resultCount, String queryPreview) {
        recordKnowledgeSearch(queryLength, resultCount);
    }

    /** v3.7.0 TFB-D3: mastery snapshot changed materially for one knowledge point. */
    default void recordMasteryChanged(
        String knowledgePoint, String level, int masteryPercent, int attempts, int passes, int failures
    ) {
    }

    /** v3.7.0 TFB-D3: ~30 minutes of app-open learning time (no input monitoring). */
    default void recordDailyActive(int activeMinutes) {
    }

    /** v3.7.0 TFB-D3: reading progress crossed a threshold for one article revision. */
    default void recordKnowledgeArticleRead(String articleId, int revision, int progressPercent) {
    }

    /** v3.7.0 TFB-D3: assistant question metadata plus a truncated question preview. */
    default void recordAssistantAsked(String question, int resultCount, String resultCode) {
    }
}
