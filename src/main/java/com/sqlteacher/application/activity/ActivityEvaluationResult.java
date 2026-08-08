package com.sqlteacher.application.activity;

import java.util.List;
import java.util.Objects;

public record ActivityEvaluationResult(
    ActivityEvaluationStatus status,
    List<ActivityCriterionResult> criteria,
    String summary,
    String reasonCode,
    String evaluatorVersion,
    String evidenceVersion,
    ActivityResourceUsage resourceUsage
) {
    public ActivityEvaluationResult {
        status = Objects.requireNonNull(status, "status must not be null");
        criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria must not be null"));
        summary = Objects.requireNonNull(summary, "summary must not be null").trim();
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        evaluatorVersion = required(evaluatorVersion, "evaluatorVersion");
        evidenceVersion = required(evidenceVersion, "evidenceVersion");
        resourceUsage = Objects.requireNonNull(resourceUsage, "resourceUsage must not be null");
        if (status == ActivityEvaluationStatus.PASSED && criteria.stream().anyMatch(item -> !item.passed())) {
            throw new IllegalArgumentException("passed evaluation cannot contain failed criteria");
        }
    }

    public boolean passed() {
        return status == ActivityEvaluationStatus.PASSED;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
