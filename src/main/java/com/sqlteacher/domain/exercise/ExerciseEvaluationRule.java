package com.sqlteacher.domain.exercise;

import java.util.List;
import java.util.Objects;

public record ExerciseEvaluationRule(
    boolean compareColumns,
    boolean compareRows,
    boolean rowOrderMatters,
    Integer expectedRowCount,
    List<String> requiredSqlKeywords
) {
    public ExerciseEvaluationRule {
        if (!compareColumns && !compareRows && expectedRowCount == null
                && (requiredSqlKeywords == null || requiredSqlKeywords.isEmpty())) {
            throw new IllegalArgumentException("At least one evaluation rule must be enabled");
        }
        if (expectedRowCount != null && expectedRowCount < 0) {
            throw new IllegalArgumentException("expectedRowCount must not be negative");
        }
        if (rowOrderMatters && !compareRows) {
            throw new IllegalArgumentException("rowOrderMatters requires compareRows");
        }
        requiredSqlKeywords = normalizeKeywords(requiredSqlKeywords);
    }

    public static ExerciseEvaluationRule exactResult(boolean rowOrderMatters) {
        return new ExerciseEvaluationRule(true, true, rowOrderMatters, null, List.of());
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        Objects.requireNonNull(keywords, "requiredSqlKeywords must not be null");
        return keywords.stream()
            .map(keyword -> Objects.requireNonNull(keyword, "keyword must not be null").trim().toUpperCase())
            .filter(keyword -> !keyword.isEmpty())
            .distinct()
            .toList();
    }
}
