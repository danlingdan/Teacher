package com.sqlteacher.domain.exercise;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ExerciseDefinition(
    String id,
    String title,
    String description,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    String datasetId,
    String referenceSql,
    ExerciseEvaluationRule evaluationRule,
    List<String> hints,
    int version,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    ExerciseType exerciseType,
    String verificationSql,
    List<String> allowedStatementTypes,
    Integer expectedAffectedRows,
    List<String> requiredTransactionKeywords,
    String triggerProbeSql
) {
    /** Statement keywords teachers may allow for STATE submissions via the DSL. */
    public static final Set<String> ALLOWABLE_STATEMENT_TYPES =
        Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER");

    public ExerciseDefinition {
        id = requireText(id, "id");
        title = requireText(title, "title");
        description = requireText(description, "description");
        knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        datasetId = requireText(datasetId, "datasetId");
        referenceSql = requireText(referenceSql, "referenceSql");
        evaluationRule = Objects.requireNonNull(evaluationRule, "evaluationRule must not be null");
        hints = normalizeHints(hints);
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        exerciseType = exerciseType == null ? ExerciseType.QUERY : exerciseType;
        verificationSql = normalizeOptionalText(verificationSql);
        allowedStatementTypes = normalizeKeywords(allowedStatementTypes, "allowedStatementTypes", true);
        requiredTransactionKeywords = normalizeKeywords(requiredTransactionKeywords, "requiredTransactionKeywords", false);
        triggerProbeSql = normalizeOptionalText(triggerProbeSql);
        if (expectedAffectedRows != null && expectedAffectedRows < 0) {
            throw new IllegalArgumentException("expectedAffectedRows must not be negative");
        }
        if (exerciseType == ExerciseType.QUERY) {
            requireAbsent(verificationSql, "verificationSql", ExerciseType.QUERY);
            requireAbsent(triggerProbeSql, "triggerProbeSql", ExerciseType.QUERY);
        } else if (verificationSql == null) {
            throw new IllegalArgumentException("verificationSql is required for " + exerciseType + " exercises");
        }
        if (exerciseType != ExerciseType.STATE && !allowedStatementTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedStatementTypes is a STATE-only field");
        }
        if (exerciseType != ExerciseType.SCRIPT && !requiredTransactionKeywords.isEmpty()) {
            throw new IllegalArgumentException("requiredTransactionKeywords is a SCRIPT-only field");
        }
        if (exerciseType == ExerciseType.QUERY || exerciseType == ExerciseType.TRIGGER) {
            if (expectedAffectedRows != null) {
                throw new IllegalArgumentException("expectedAffectedRows applies to STATE and SCRIPT exercises only");
            }
        }
        if (exerciseType == ExerciseType.TRIGGER && triggerProbeSql == null) {
            throw new IllegalArgumentException("triggerProbeSql is required for TRIGGER exercises");
        }
    }

    /** Compatibility view for pre-v3.3 callers: every QUERY-era constructor arity. */
    public ExerciseDefinition(
        String id,
        String title,
        String description,
        String knowledgePoint,
        ExerciseDifficulty difficulty,
        String datasetId,
        String referenceSql,
        ExerciseEvaluationRule evaluationRule,
        List<String> hints,
        int version,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, title, description, knowledgePoint, difficulty, datasetId, referenceSql,
            evaluationRule, hints, version, enabled, createdAt, updatedAt,
            ExerciseType.QUERY, null, List.of(), null, List.of(), null);
    }

    /** Returns the statement types a student may submit; empty means the type default. */
    public List<String> effectiveAllowedStatementTypes() {
        return allowedStatementTypes.isEmpty() && exerciseType == ExerciseType.STATE
            ? List.of("INSERT", "UPDATE", "DELETE")
            : allowedStatementTypes;
    }

    private static List<String> normalizeKeywords(List<String> values, String fieldName, boolean enforceVocabulary) {
        Objects.requireNonNull(values, fieldName + " must not be null");
        List<String> normalized = values.stream()
            .map(value -> Objects.requireNonNull(value, fieldName + " entry must not be null").trim()
                .toUpperCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
        if (enforceVocabulary) {
            for (String value : normalized) {
                if (!ALLOWABLE_STATEMENT_TYPES.contains(value)) {
                    throw new IllegalArgumentException("Unknown allowed statement type: " + value);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static void requireAbsent(String value, String fieldName, ExerciseType type) {
        if (value != null) {
            throw new IllegalArgumentException(fieldName + " must be absent for " + type + " exercises");
        }
    }

    private static List<String> normalizeHints(List<String> values) {
        Objects.requireNonNull(values, "hints must not be null");
        List<String> normalized = values.stream()
            .map(value -> requireText(value, "hint"))
            .distinct()
            .toList();
        if (normalized.size() > 3) {
            throw new IllegalArgumentException("At most three hint levels are supported");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
