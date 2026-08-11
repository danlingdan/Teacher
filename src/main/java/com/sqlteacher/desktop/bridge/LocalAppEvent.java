package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record LocalAppEvent(String type, JsonNode payload) {
    public LocalAppEvent {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type must not be blank");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
