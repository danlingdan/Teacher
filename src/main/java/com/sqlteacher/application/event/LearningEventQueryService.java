package com.sqlteacher.application.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Query service for learning events to support analytics and debugging.
 */
public interface LearningEventQueryService {

    /**
     * Query events by type within a time range.
     *
     * @param type the event type to filter by
     * @param start the start of the time range (inclusive), or null for no lower bound
     * @param end the end of the time range (inclusive), or null for no upper bound
     * @return list of matching events, never null
     */
    List<QueriedLearningEvent> queryEventsByType(LearningEventType type, Instant start, Instant end);

    /**
     * Query events by connection ID within a time range.
     *
     * @param connectionId the connection ID to filter by
     * @param start the start of the time range (inclusive), or null for no lower bound
     * @param end the end of the time range (inclusive), or null for no upper bound
     * @return list of matching events, never null
     */
    List<QueriedLearningEvent> queryEventsByConnection(String connectionId, Instant start, Instant end);

    /**
     * Get event statistics within a time range.
     *
     * @param start the start of the time range (inclusive), or null for no lower bound
     * @param end the end of the time range (inclusive), or null for no upper bound
     * @return statistics about events in the time range
     */
    EventStatistics getEventStatistics(Instant start, Instant end);

    /**
     * A read-only representation of a queried learning event.
     */
    record QueriedLearningEvent(
        long id,
        LearningEventType type,
        Instant occurredAt,
        String connectionId,
        boolean successful,
        Map<String, String> attributes,
        Instant createdAt
    ) {}

    /**
     * Statistics about learning events in a time range.
     */
    record EventStatistics(
        long totalEvents,
        long successfulEvents,
        long failedEvents,
        Map<LearningEventType, Long> eventsByType,
        Map<String, Long> eventsByConnection
    ) {}
}
