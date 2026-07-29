package com.sqlteacher.application.ai;

import java.net.URI;
import java.util.Objects;

public record AiProviderProfileDraft(
    String id,
    String displayName,
    AiProviderKind kind,
    URI endpoint,
    String model,
    boolean enabled
) {
    public AiProviderProfileDraft {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        model = requireText(model, "model");
        // Reuse the frozen profile contract for endpoint validation.
        new AiProviderProfile(id, displayName, kind, endpoint, model, enabled, "pending");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.strip();
    }
}
