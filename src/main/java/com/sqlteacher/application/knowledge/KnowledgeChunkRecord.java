package com.sqlteacher.application.knowledge;

import java.util.List;

public record KnowledgeChunkRecord(
    String id,
    String documentId,
    String articleId,
    String revisionId,
    int revision,
    int chunkIndex,
    String title,
    String courseTitle,
    String sectionTitle,
    List<String> knowledgePoints,
    String headingPath,
    String content,
    KnowledgeVisibility visibility,
    String ownerId
) {
    public KnowledgeChunkRecord {
        if (id == null || id.isBlank() || documentId == null || documentId.isBlank()
            || articleId == null || articleId.isBlank() || revisionId == null || revisionId.isBlank()
            || revision < 1 || chunkIndex < 0 || title == null || title.isBlank()
            || courseTitle == null || courseTitle.isBlank() || sectionTitle == null || sectionTitle.isBlank()
            || content == null || content.isBlank() || visibility == null || ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("knowledge chunk values are invalid");
        }
        headingPath = headingPath == null ? "" : headingPath.trim();
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
    }
}
