package com.sqlteacher.infrastructure.ai.dto;

import java.util.Objects;

public record OllamaSqlErrorResponse(
    String errorCause,
    String correctionSuggestion,
    String correctedSql
) {
    public OllamaSqlErrorResponse {
        Objects.requireNonNull(errorCause, "errorCause must not be null");
        Objects.requireNonNull(correctionSuggestion, "correctionSuggestion must not be null");
        Objects.requireNonNull(correctedSql, "correctedSql must not be null");
    }
}