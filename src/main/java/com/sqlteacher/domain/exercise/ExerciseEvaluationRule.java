package com.sqlteacher.domain.exercise;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Deterministic comparison rules for an exercise. v3.3 adds per-criterion display
 * weights (0-100 score feedback only; passing still requires every criterion) and
 * PLAN_KEYWORDS assertions against EXPLAIN QUERY PLAN output on the verification query.
 */
public record ExerciseEvaluationRule(
    boolean compareColumns,
    boolean compareRows,
    boolean rowOrderMatters,
    Integer expectedRowCount,
    List<String> requiredSqlKeywords,
    Map<String, Integer> criterionWeights,
    List<String> planKeywords
) {
    public ExerciseEvaluationRule {
        if (!compareColumns && !compareRows && expectedRowCount == null
                && (requiredSqlKeywords == null || requiredSqlKeywords.isEmpty())
                && (planKeywords == null || planKeywords.isEmpty())) {
            throw new IllegalArgumentException("At least one evaluation rule must be enabled");
        }
        if (expectedRowCount != null && expectedRowCount < 0) {
            throw new IllegalArgumentException("expectedRowCount must not be negative");
        }
        if (rowOrderMatters && !compareRows) {
            throw new IllegalArgumentException("rowOrderMatters requires compareRows");
        }
        requiredSqlKeywords = normalizeKeywords(requiredSqlKeywords);
        criterionWeights = normalizeWeights(criterionWeights);
        planKeywords = normalizeKeywords(planKeywords);
    }

    public static ExerciseEvaluationRule exactResult(boolean rowOrderMatters) {
        return new ExerciseEvaluationRule(true, true, rowOrderMatters, null, List.of(), Map.of(), List.of());
    }

    /** Compatibility view for pre-v3.3 callers without weights or plan keywords. */
    public ExerciseEvaluationRule(
        boolean compareColumns,
        boolean compareRows,
        boolean rowOrderMatters,
        Integer expectedRowCount,
        List<String> requiredSqlKeywords
    ) {
        this(compareColumns, compareRows, rowOrderMatters, expectedRowCount, requiredSqlKeywords, Map.of(), List.of());
    }

    /** Returns the display weight of a scored criterion; unset criteria weigh 1. */
    public int weightOf(String criterion) {
        Integer weight = criterionWeights.get(criterion.trim().toUpperCase(Locale.ROOT));
        return weight == null ? 1 : weight;
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        Objects.requireNonNull(keywords, "keywords must not be null");
        return keywords.stream()
            .map(keyword -> Objects.requireNonNull(keyword, "keyword must not be null").trim())
            .filter(keyword -> !keyword.isEmpty())
            .map(keyword -> keyword.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
    }

    private static Map<String, Integer> normalizeWeights(Map<String, Integer> weights) {
        Objects.requireNonNull(weights, "criterionWeights must not be null");
        if (weights.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> normalized = weights.entrySet().stream()
            .collect(Collectors.toMap(
                entry -> Objects.requireNonNull(entry.getKey(), "weight key must not be null").trim()
                    .toUpperCase(Locale.ROOT),
                entry -> {
                    Integer weight = entry.getValue();
                    if (weight == null || weight <= 0) {
                        throw new IllegalArgumentException("criterion weights must be positive");
                    }
                    return weight;
                },
                (first, second) -> first,
                java.util.LinkedHashMap::new
            ));
        return java.util.Collections.unmodifiableMap(normalized);
    }
}
