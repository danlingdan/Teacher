package com.sqlteacher.application.activity;

import java.util.Objects;

public record ActivityCriterionResult(String criterion, boolean passed, String reasonCode, String feedback) {
    public ActivityCriterionResult {
        criterion = required(criterion, "criterion");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        feedback = required(feedback, "feedback");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
