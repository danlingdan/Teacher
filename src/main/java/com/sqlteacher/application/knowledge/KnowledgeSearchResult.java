package com.sqlteacher.application.knowledge;

public record KnowledgeSearchResult(
    String documentId,
    String title,
    String sourceName,
    int chunkIndex,
    String snippet,
    double relevance
) {
    public KnowledgeSearchResult {
        if (documentId == null || documentId.isBlank() || title == null || title.isBlank()
            || sourceName == null || sourceName.isBlank() || chunkIndex < 0
            || snippet == null || snippet.isBlank() || !Double.isFinite(relevance)) {
            throw new IllegalArgumentException("knowledge search result values are invalid");
        }
    }
}
