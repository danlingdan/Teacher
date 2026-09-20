package com.sqlteacher.application.collaboration;

import java.util.List;
import java.util.Map;

/**
 * v3.7.0 TFB-S2: class-scoped learning overview that extends {@link ClassLearningSummary}
 * with recent-activity statistics and a short daily trend. Served as a separate endpoint so
 * the legacy analytics response stays byte-compatible for older desktop clients.
 */
public record ClassLearningOverview(
    ClassLearningSummary summary,
    long activeStudents7d,
    Map<String, Long> eventsByType,
    List<DailyActivity> trend
) {
    public ClassLearningOverview {
        if (summary == null || activeStudents7d < 0 || eventsByType == null || trend == null) {
            throw new IllegalArgumentException("Invalid class learning overview");
        }
        eventsByType = Map.copyOf(eventsByType);
        trend = List.copyOf(trend);
    }

    /** One UTC day of class activity inside the 14-day trend window. */
    public record DailyActivity(String date, long activeStudents, long events) {
        public DailyActivity {
            if (date == null || date.isBlank() || activeStudents < 0 || events < 0) {
                throw new IllegalArgumentException("Invalid daily activity row");
            }
        }
    }
}
