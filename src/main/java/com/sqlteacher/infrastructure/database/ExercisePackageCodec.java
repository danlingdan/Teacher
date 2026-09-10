package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.util.List;

final class ExercisePackageCodec {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    String encodeRule(ExerciseEvaluationRule rule) {
        try {
            return mapper.writeValueAsString(RuleData.from(rule));
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Failed to encode evaluation rule", error);
        }
    }

    ExerciseEvaluationRule decodeRule(String json) {
        try {
            return mapper.readValue(json, RuleData.class).toDomain();
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Invalid stored evaluation rule", error);
        }
    }

    /** Serializes the per-exercise-type grading configuration (empty object for QUERY). */
    String encodeTypeConfig(String verificationSql, List<String> allowedStatementTypes,
                            Integer expectedAffectedRows, List<String> requiredTransactionKeywords,
                            String triggerProbeSql) {
        try {
            return mapper.writeValueAsString(new TypeConfigData(
                verificationSql, allowedStatementTypes, expectedAffectedRows,
                requiredTransactionKeywords, triggerProbeSql
            ));
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Failed to encode exercise type config", error);
        }
    }

    TypeConfigData decodeTypeConfig(String json) {
        try {
            TypeConfigData data = mapper.readValue(json == null || json.isBlank() ? "{}" : json, TypeConfigData.class);
            return new TypeConfigData(
                data.verificationSql(),
                data.allowedStatementTypes() == null ? List.of() : List.copyOf(data.allowedStatementTypes()),
                data.expectedAffectedRows(),
                data.requiredTransactionKeywords() == null ? List.of() : List.copyOf(data.requiredTransactionKeywords()),
                data.triggerProbeSql()
            );
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Invalid stored exercise type config", error);
        }
    }

    String encodeHints(List<String> hints) {
        try {
            return mapper.writeValueAsString(hints);
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Failed to encode hints", error);
        }
    }

    List<String> decodeHints(String json) {
        try {
            return List.copyOf(mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (JsonProcessingException | NullPointerException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Invalid stored hints", error);
        }
    }

    private record RuleData(
        boolean compareColumns,
        boolean compareRows,
        boolean rowOrderMatters,
        Integer expectedRowCount,
        List<String> requiredSqlKeywords
    ) {
        static RuleData from(ExerciseEvaluationRule rule) {
            return new RuleData(
                rule.compareColumns(), rule.compareRows(), rule.rowOrderMatters(),
                rule.expectedRowCount(), rule.requiredSqlKeywords()
            );
        }

        ExerciseEvaluationRule toDomain() {
            return new ExerciseEvaluationRule(
                compareColumns, compareRows, rowOrderMatters, expectedRowCount, requiredSqlKeywords
            );
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TypeConfigData(
        String verificationSql,
        List<String> allowedStatementTypes,
        Integer expectedAffectedRows,
        List<String> requiredTransactionKeywords,
        String triggerProbeSql
    ) {
    }
}
