package com.sqlteacher.application.knowledge;

import java.time.Instant;
import java.util.List;

public record CourseKnowledgeRevision(
    String id,
    String articleId,
    int revision,
    String title,
    String content,
    String contentHash,
    String sourceName,
    List<String> headingPath,
    Instant createdAt
) {
    public CourseKnowledgeRevision {
        if (id == null || id.isBlank() || articleId == null || articleId.isBlank() || revision < 1
            || title == null || title.isBlank() || content == null || content.isBlank()
            || contentHash == null || contentHash.isBlank() || sourceName == null || sourceName.isBlank()
            || createdAt == null) {
            throw new IllegalArgumentException("course knowledge revision values are invalid");
        }
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }
}
