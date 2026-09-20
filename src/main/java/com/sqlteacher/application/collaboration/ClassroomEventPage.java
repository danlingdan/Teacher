package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * v3.7.0 TFB-S1: one page of a student's synced learning events as visible to the classroom
 * teacher. Attributes are the upload-allowlisted evidence keys; results and raw prompts never
 * appear here. {@code nextCursor} is the sync version to pass for the following page, or null
 * when this page is the last one.
 */
public record ClassroomEventPage(java.util.List<ClassroomEventEntry> entries, Long nextCursor) {
    public ClassroomEventPage {
        if (entries == null) throw new IllegalArgumentException("entries must not be null");
        entries = List.copyOf(entries);
    }

    public record ClassroomEventEntry(
        String eventId,
        String eventType,
        Instant occurredAt,
        boolean successful,
        Map<String, String> attributes
    ) {
        public ClassroomEventEntry {
            if (eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank()
                || occurredAt == null || attributes == null) {
                throw new IllegalArgumentException("Invalid classroom event entry");
            }
            attributes = Map.copyOf(attributes);
        }
    }
}
