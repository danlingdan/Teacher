package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.util.List;

final class ExercisePackageCodec {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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
}
