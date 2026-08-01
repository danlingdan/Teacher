package com.sqlteacher.application.knowledge;

import java.util.List;

public record CourseKnowledgeDetail(
    CourseKnowledgeArticle article,
    CourseKnowledgeRevision revision,
    List<CourseKnowledgeRevision> history
) {
    public CourseKnowledgeDetail {
        if (article == null || revision == null) {
            throw new IllegalArgumentException("article and revision are required");
        }
        history = history == null ? List.of() : List.copyOf(history);
    }
}
