package com.sqlteacher.application.event;

import com.sqlteacher.application.risk.SqlRiskLevel;
import com.sqlteacher.application.activity.SqlActivityEvaluator;
import com.sqlteacher.domain.activity.ActivityType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DefaultLearningEventService implements LearningEventService {
    private final LearningEventRecorder recorder;
    private final Clock clock;
    private final LearningEventOwnerProvider ownerProvider;

    public DefaultLearningEventService(LearningEventRecorder recorder) {
        this(recorder, Clock.systemUTC(), () -> LearningEventOwnerProvider.GUEST_OWNER);
    }

    public DefaultLearningEventService(
        LearningEventRecorder recorder,
        LearningEventOwnerProvider ownerProvider
    ) {
        this(recorder, Clock.systemUTC(), ownerProvider);
    }

    DefaultLearningEventService(LearningEventRecorder recorder, Clock clock) {
        this(recorder, clock, () -> LearningEventOwnerProvider.GUEST_OWNER);
    }

    DefaultLearningEventService(
        LearningEventRecorder recorder,
        Clock clock,
        LearningEventOwnerProvider ownerProvider
    ) {
        this.recorder = Objects.requireNonNull(recorder, "recorder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ownerProvider = Objects.requireNonNull(ownerProvider, "ownerProvider must not be null");
    }

    @Override
    public void recordSqlExecution(
        String connectionId,
        boolean successful,
        String statementType,
        Duration duration,
        int resultCount,
        String errorCode
    ) {
        recordSqlExecution(connectionId, successful, statementType, duration, resultCount, errorCode, null, null);
    }

    @Override
    public void recordSqlExecution(
        String connectionId,
        boolean successful,
        String statementType,
        Duration duration,
        int resultCount,
        String errorCode,
        String sqlText,
        String dialect
    ) {
        validateConnectionId(connectionId);
        validateText(statementType, "statementType");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must not be negative");
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("statementType", statementType);
        attributes.put("durationMs", Long.toString(duration.toMillis()));
        attributes.put("resultCount", Integer.toString(resultCount));
        putIfPresent(attributes, "errorCode", errorCode);
        putSqlEvidence(attributes, sqlText);
        putIfPresent(attributes, "dialect", dialect);
        record(LearningEventType.SQL_EXECUTION, connectionId, successful, attributes);
    }

    @Override
    public void recordSqlRiskBlocked(
        String connectionId,
        String statementType,
        SqlRiskLevel riskLevel,
        boolean multiStatement
    ) {
        validateConnectionId(connectionId);
        validateText(statementType, "statementType");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");

        record(
            LearningEventType.SQL_RISK_BLOCKED,
            connectionId,
            false,
            Map.of(
                "statementType", statementType,
                "riskLevel", riskLevel.name(),
                "multiStatement", Boolean.toString(multiStatement)
            )
        );
    }

    @Override
    public void recordAiGeneration(
        String connectionId,
        boolean successful,
        String model,
        String promptVersion,
        String errorCode
    ) {
        recordAiGeneration(connectionId, successful, model, promptVersion, errorCode, null);
    }

    @Override
    public void recordAiGeneration(
        String connectionId,
        boolean successful,
        String model,
        String promptVersion,
        String errorCode,
        String generatedSql
    ) {
        validateConnectionId(connectionId);
        validateText(model, "model");
        validateText(promptVersion, "promptVersion");

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("model", model);
        attributes.put("promptVersion", promptVersion);
        putIfPresent(attributes, "errorCode", errorCode);
        putSqlEvidence(attributes, generatedSql);
        record(
            successful ? LearningEventType.AI_SQL_GENERATED : LearningEventType.AI_GENERATION_FAILED,
            connectionId,
            successful,
            attributes
        );
    }

    @Override
    public void recordSqlConfirmationIssued(String connectionId, String statementType) {
        validateConnectionId(connectionId);
        validateText(statementType, "statementType");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("statementType", statementType);
        record(LearningEventType.SQL_CONFIRMATION_ISSUED, connectionId, true, attributes);
    }

    @Override
    public void recordSqlConfirmed(String connectionId, String statementType) {
        validateConnectionId(connectionId);
        validateText(statementType, "statementType");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("statementType", statementType);
        record(LearningEventType.SQL_CONFIRMED, connectionId, true, attributes);
    }

    @Override
    public void recordSqlConfirmationCancelled(String connectionId, String reason) {
        validateConnectionId(connectionId);
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "reason", reason);
        record(LearningEventType.SQL_CONFIRMATION_CANCELLED, connectionId, false, attributes);
    }

    @Override
    public void recordExerciseAttempt(
        String exerciseId,
        String status,
        boolean successful,
        Duration duration,
        String errorCode
    ) {
        recordActivityEvaluation(
            exerciseId, ActivityType.SQL, status, successful, duration,
            SqlActivityEvaluator.VERSION, SqlActivityEvaluator.EVIDENCE_VERSION, errorCode
        );
    }

    @Override
    public void recordExerciseAttempt(
        String exerciseId,
        String status,
        boolean successful,
        Duration duration,
        String errorCode,
        Integer score,
        String sqlText
    ) {
        recordEvaluation(
            exerciseId, ActivityType.SQL, status, successful, duration,
            SqlActivityEvaluator.VERSION, SqlActivityEvaluator.EVIDENCE_VERSION, errorCode, score, sqlText
        );
    }

    @Override
    public void recordActivityEvaluation(
        String activityId,
        ActivityType activityType,
        String status,
        boolean successful,
        Duration duration,
        String evaluatorVersion,
        String evidenceVersion,
        String reasonCode
    ) {
        recordEvaluation(activityId, activityType, status, successful, duration,
            evaluatorVersion, evidenceVersion, reasonCode, null, null);
    }

    private void recordEvaluation(
        String activityId,
        ActivityType activityType,
        String status,
        boolean successful,
        Duration duration,
        String evaluatorVersion,
        String evidenceVersion,
        String reasonCode,
        Integer score,
        String sqlText
    ) {
        validateText(activityId, "activityId");
        Objects.requireNonNull(activityType, "activityType must not be null");
        validateText(status, "status");
        Objects.requireNonNull(duration, "duration must not be null");
        validateText(evaluatorVersion, "evaluatorVersion");
        validateText(evidenceVersion, "evidenceVersion");
        if (duration.isNegative()) throw new IllegalArgumentException("duration must not be negative");
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("activityId", activityId);
        attributes.put("activityType", activityType.name());
        attributes.put("status", status);
        attributes.put("durationMs", Long.toString(duration.toMillis()));
        attributes.put("evaluatorVersion", evaluatorVersion);
        attributes.put("evidenceVersion", evidenceVersion);
        putIfPresent(attributes, "reasonCode", reasonCode);
        if (score != null) {
            attributes.put("score", score.toString());
        }
        if (activityType == ActivityType.SQL) {
            attributes.put("exerciseId", activityId);
            putIfPresent(attributes, "errorCode", reasonCode);
            putSqlEvidence(attributes, sqlText);
        }
        LearningEventType type = switch (status) {
            case "PASSED" -> activityType == ActivityType.SQL
                ? LearningEventType.EXERCISE_PASSED : LearningEventType.ACTIVITY_PASSED;
            case "FAILED" -> activityType == ActivityType.SQL
                ? LearningEventType.EXERCISE_FAILED : LearningEventType.ACTIVITY_FAILED;
            default -> activityType == ActivityType.SQL
                ? LearningEventType.EXERCISE_ATTEMPT : LearningEventType.ACTIVITY_ATTEMPT;
        };
        record(type, activityType == ActivityType.SQL ? "exercise" : "activity", successful, attributes);
    }

    @Override
    public void recordExerciseHint(String exerciseId, int hintLevel) {
        validateText(exerciseId, "exerciseId");
        if (hintLevel < 1) {
            throw new IllegalArgumentException("hintLevel must be positive");
        }
        record(
            LearningEventType.EXERCISE_HINT_USED,
            "exercise",
            true,
            Map.of("exerciseId", exerciseId, "hintLevel", Integer.toString(hintLevel))
        );
    }

    @Override
    public void recordKnowledgeSearch(int queryLength, int resultCount) {
        recordKnowledgeSearch(queryLength, resultCount, null);
    }

    @Override
    public void recordKnowledgeSearch(int queryLength, int resultCount, String queryPreview) {
        if (queryLength < 1 || resultCount < 0) {
            throw new IllegalArgumentException("knowledge search metrics are invalid");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("queryLength", Integer.toString(queryLength));
        attributes.put("resultCount", Integer.toString(resultCount));
        putIfPresent(attributes, "queryPreview", queryPreview);
        record(
            LearningEventType.KNOWLEDGE_SEARCHED,
            "knowledge",
            true,
            attributes
        );
    }

    @Override
    public void recordMasteryChanged(
        String knowledgePoint, String level, int masteryPercent, int attempts, int passes, int failures
    ) {
        validateText(knowledgePoint, "knowledgePoint");
        validateText(level, "level");
        if (masteryPercent < 0 || masteryPercent > 100) {
            throw new IllegalArgumentException("masteryPercent must be between 0 and 100");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("knowledgePoint", knowledgePoint);
        attributes.put("level", level);
        attributes.put("masteryPercent", Integer.toString(masteryPercent));
        attributes.put("attempts", Integer.toString(attempts));
        attributes.put("passes", Integer.toString(passes));
        attributes.put("failures", Integer.toString(failures));
        record(LearningEventType.MASTERY_CHANGED, "mastery", true, attributes);
    }

    @Override
    public void recordDailyActive(int activeMinutes) {
        if (activeMinutes < 1) {
            throw new IllegalArgumentException("activeMinutes must be positive");
        }
        record(LearningEventType.DAILY_ACTIVE, "activity", true,
            Map.of("activeMinutes", Integer.toString(activeMinutes)));
    }

    @Override
    public void recordKnowledgeArticleRead(String articleId, int revision, int progressPercent) {
        validateText(articleId, "articleId");
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be between 0 and 100");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("articleId", articleId);
        attributes.put("revision", Integer.toString(revision));
        attributes.put("progressPercent", Integer.toString(progressPercent));
        record(LearningEventType.KNOWLEDGE_ARTICLE_READ, "knowledge", true, attributes);
    }

    @Override
    public void recordAssistantAsked(String question, int resultCount, String resultCode) {
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must not be negative");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "questionPreview", question);
        attributes.put("resultCount", Integer.toString(resultCount));
        putIfPresent(attributes, "resultCode", resultCode);
        record(LearningEventType.AI_ASSISTANT_ASKED, "knowledge", true, attributes);
    }

    private void record(
        LearningEventType type,
        String connectionId,
        boolean successful,
        Map<String, String> attributes
    ) {
        Map<String, String> ownedAttributes = new LinkedHashMap<>(attributes);
        ownedAttributes.put(LearningEventOwnerProvider.OWNER_ATTRIBUTE, ownerProvider.currentOwnerId());
        recorder.record(new LearningEvent(type, clock.instant(), connectionId, successful, ownedAttributes));
    }

    private static void validateConnectionId(String connectionId) {
        validateText(connectionId, "connectionId");
    }

    private static void validateText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void putIfPresent(Map<String, String> attributes, String name, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(name, value);
        }
    }

    /**
     * v3.7.0 TFB-D2: events may carry truncated SQL text plus a deterministic hash of the full
     * text so a teacher can correlate an AI draft with the execution that actually ran.
     */
    private static void putSqlEvidence(Map<String, String> attributes, String sqlText) {
        if (sqlText == null || sqlText.isBlank()) return;
        attributes.put("sqlText", sqlText);
        attributes.put("sqlHash", sqlHash(sqlText));
    }

    private static String sqlHash(String sqlText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(sqlText.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte byteValue : digest) {
                hex.append(Character.forDigit((byteValue >> 4) & 0xF, 16));
                hex.append(Character.forDigit(byteValue & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 digest unavailable", error);
        }
    }
}
