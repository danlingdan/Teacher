package com.sqlteacher.application.nl2sql;

import java.util.Objects;

public record SqlErrorExplanation(
    boolean success,
    String errorCause,
    String correctionSuggestion,
    String correctedSql,
    String model
) {
    public SqlErrorExplanation {
        Objects.requireNonNull(errorCause, "errorCause must not be null");
        Objects.requireNonNull(correctionSuggestion, "correctionSuggestion must not be null");
        Objects.requireNonNull(correctedSql, "correctedSql must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }

    public static SqlErrorExplanation success(String errorCause, String correctionSuggestion, String correctedSql, String model) {
        return new SqlErrorExplanation(true, errorCause, correctionSuggestion, correctedSql, model);
    }

    public static SqlErrorExplanation failure(String errorCause, String model) {
        return new SqlErrorExplanation(false, errorCause, "", "", model);
    }
}