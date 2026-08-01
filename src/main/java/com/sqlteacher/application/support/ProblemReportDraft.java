package com.sqlteacher.application.support;

import java.util.Objects;

public record ProblemReportDraft(String idempotencyKey, Type type, Severity severity, String summary,
                                 String description, String reproductionSteps, String expectedResult,
                                 String actualResult, String contact, DiagnosticSelection diagnostics) {
    public enum Type { BUG, UPDATE_PROBLEM, USABILITY, SUGGESTION, OTHER }
    public enum Severity { DATA_OR_STARTUP_RISK, MAIN_FLOW_BLOCKED, PARTIAL_FAILURE, MINOR }

    public ProblemReportDraft {
        idempotencyKey = required(idempotencyKey, 80, "idempotencyKey");
        Objects.requireNonNull(type); Objects.requireNonNull(severity);
        summary = required(summary, 160, "summary");
        description = required(description, 4000, "description");
        reproductionSteps = optional(reproductionSteps, 4000);
        expectedResult = optional(expectedResult, 2000);
        actualResult = optional(actualResult, 2000);
        contact = optional(contact, 254);
        diagnostics = diagnostics == null ? DiagnosticSelection.minimum() : diagnostics;
    }

    private static String required(String value, int max, String name) {
        String cleaned = optional(value, max);
        if (cleaned.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return cleaned;
    }
    private static String optional(String value, int max) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.length() > max) throw new IllegalArgumentException("field is too long");
        return cleaned;
    }
}
