package com.sqlteacher.application.event;

import java.time.Instant;
import java.util.Map;

public record LearningEvent(
    LearningEventType type,
    Instant occurredAt,
    String connectionId,
    boolean successful,
    Map<String, String> attributes
) {
    public LearningEvent {
        attributes = Map.copyOf(attributes);
    }
}
