package com.sqlteacher.application.collaboration;

import java.time.Instant;

/** Aggregated, class-scoped teaching record that never exposes another class's events. */
public record ClassLearningSummary(String classroomId, int studentCount, int activeStudentCount,
                                   int syncedEvents, int successfulEvents, Instant generatedAt) {
    public ClassLearningSummary {
        if (classroomId == null || classroomId.isBlank() || studentCount < 0 || activeStudentCount < 0
            || syncedEvents < 0 || successfulEvents < 0 || generatedAt == null) {
            throw new IllegalArgumentException("Invalid class learning summary");
        }
    }
}
