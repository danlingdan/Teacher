package com.sqlteacher.application.knowledge;

import java.util.List;

public interface HybridKnowledgeRetrievalService {
    RetrievalResponse retrieve(String query, CourseKnowledgeSearchFilter filter, int limit);

    record RetrievalResponse(List<KnowledgeSearchResult> results, String mode, boolean degraded, String message) {
        public RetrievalResponse {
            results = results == null ? List.of() : List.copyOf(results);
            if (mode == null || mode.isBlank()) throw new IllegalArgumentException("retrieval mode must not be blank");
            message = message == null ? "" : message.trim();
        }
    }
}
