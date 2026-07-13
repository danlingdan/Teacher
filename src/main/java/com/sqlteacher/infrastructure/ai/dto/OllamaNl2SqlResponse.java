package com.sqlteacher.infrastructure.ai.dto;

import java.util.Objects;

public record OllamaNl2SqlResponse(
    String sqlDraft,
    String intent,
    String explanation
) {
    public OllamaNl2SqlResponse {
        Objects.requireNonNull(sqlDraft, "sqlDraft must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(explanation, "explanation must not be null");
    }
}