package com.sqlteacher.application.knowledge;

import java.time.Instant;

public record KnowledgeDocument(
    String id,
    String title,
    String sourceName,
    int chunkCount,
    Instant importedAt
) {
    public KnowledgeDocument {
        if (id == null || id.isBlank() || title == null || title.isBlank()
            || sourceName == null || sourceName.isBlank() || chunkCount < 1 || importedAt == null) {
            throw new IllegalArgumentException("knowledge document values are invalid");
        }
    }
}
