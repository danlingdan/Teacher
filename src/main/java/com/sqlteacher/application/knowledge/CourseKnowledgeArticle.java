package com.sqlteacher.application.knowledge;

import java.time.Instant;
import java.util.List;

public record CourseKnowledgeArticle(
    String id,
    String documentId,
    String courseTitle,
    String sectionTitle,
    String title,
    KnowledgeVisibility visibility,
    int currentRevision,
    List<String> knowledgePoints,
    String contentHash,
    Instant updatedAt
) {
    public CourseKnowledgeArticle {
        if (id == null || id.isBlank() || documentId == null || documentId.isBlank()
            || courseTitle == null || courseTitle.isBlank() || sectionTitle == null || sectionTitle.isBlank()
            || title == null || title.isBlank() || visibility == null || currentRevision < 1
            || contentHash == null || contentHash.isBlank() || updatedAt == null) {
            throw new IllegalArgumentException("course knowledge article values are invalid");
        }
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
    }
}
