package com.sqlteacher.application.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record LearningEvent(
    LearningEventType type,
    Instant occurredAt,
    String connectionId,
    boolean successful,
    Map<String, String> attributes
) {
    public LearningEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(attributes);
    }
}
