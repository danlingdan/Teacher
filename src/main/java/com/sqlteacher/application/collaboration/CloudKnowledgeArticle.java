package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record CloudKnowledgeArticle(
    String id, String courseId, String sectionId, String title, String contentHash,
    String visibility, int revision, String createdBy, Instant updatedAt
) {
    public CloudKnowledgeArticle {
        if (id == null || id.isBlank() || courseId == null || courseId.isBlank() || title == null || title.isBlank()
            || contentHash == null || contentHash.isBlank() || visibility == null || visibility.isBlank()
            || revision < 1 || createdBy == null || createdBy.isBlank() || updatedAt == null) {
            throw new IllegalArgumentException("cloud knowledge article values are invalid");
        }
        sectionId = sectionId == null ? "" : sectionId.trim();
    }
}
