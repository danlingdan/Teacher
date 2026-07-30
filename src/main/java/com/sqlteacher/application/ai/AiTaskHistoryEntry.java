package com.sqlteacher.application.ai;

import java.time.Instant;

public record AiTaskHistoryEntry(
    String id,
    Instant createdAt,
    AiTaskType taskType,
    String model,
    boolean successful,
    String resultCode,
    long durationMillis,
    String promptVersion,
    boolean favorite,
    String savedDraft
) {
    public AiTaskHistoryEntry {
        savedDraft = savedDraft == null ? "" : savedDraft;
    }
}
