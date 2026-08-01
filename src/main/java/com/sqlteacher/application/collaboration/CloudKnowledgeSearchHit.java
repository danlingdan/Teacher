package com.sqlteacher.application.collaboration;

public record CloudKnowledgeSearchHit(
    String articleId, String title, int revision, int chunkIndex, String snippet, double score
) {
    public CloudKnowledgeSearchHit {
        if (articleId == null || articleId.isBlank() || title == null || title.isBlank() || revision < 1
            || chunkIndex < 0 || snippet == null || snippet.isBlank() || !Double.isFinite(score)) {
            throw new IllegalArgumentException("cloud knowledge search hit values are invalid");
        }
    }
}
