package com.sqlteacher.application.ai;

import java.util.List;

public interface AiTaskHistoryService {
    List<AiTaskHistoryEntry> recent();
    void record(AiTaskHistoryEntry entry);
    void favorite(String id, boolean favorite, String draftContent);
    int requestsToday();
}
