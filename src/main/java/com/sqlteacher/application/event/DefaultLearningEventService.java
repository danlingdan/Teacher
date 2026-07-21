package com.sqlteacher.application.event;

import com.sqlteacher.application.risk.SqlRiskLevel;

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
        validateConnectionId(connectionId);
        validateText(model, "model");
        validateText(promptVersion, "promptVersion");

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("model", model);
        attributes.put("promptVersion", promptVersion);
        putIfPresent(attributes, "errorCode", errorCode);
        record(
            successful ? LearningEventType.AI_SQL_GENERATED : LearningEventType.AI_GENERATION_FAILED,
            connectionId,
            successful,
            attributes
        );
    }

    @Override
    public void recordExerciseAttempt(
        String exerciseId,
        String status,
        boolean successful,
        Duration duration,
        String errorCode
    ) {
        validateText(exerciseId, "exerciseId");
        validateText(status, "status");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("exerciseId", exerciseId);
        attributes.put("status", status);
        attributes.put("durationMs", Long.toString(duration.toMillis()));
        putIfPresent(attributes, "errorCode", errorCode);
        LearningEventType type = switch (status) {
            case "PASSED" -> LearningEventType.EXERCISE_PASSED;
            case "FAILED" -> LearningEventType.EXERCISE_FAILED;
            default -> LearningEventType.EXERCISE_ATTEMPT;
        };
        record(type, "exercise", successful, attributes);
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
        if (queryLength < 1 || resultCount < 0) {
            throw new IllegalArgumentException("knowledge search metrics are invalid");
        }
        record(
            LearningEventType.KNOWLEDGE_SEARCHED,
            "knowledge",
            true,
            Map.of("queryLength", Integer.toString(queryLength), "resultCount", Integer.toString(resultCount))
        );
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
}
