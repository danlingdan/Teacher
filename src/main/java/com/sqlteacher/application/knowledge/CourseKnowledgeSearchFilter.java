package com.sqlteacher.application.knowledge;

public record CourseKnowledgeSearchFilter(
    String courseTitle,
    String sectionTitle,
    String knowledgePoint,
    boolean includePrivate
) {
    public CourseKnowledgeSearchFilter {
        courseTitle = normalize(courseTitle);
        sectionTitle = normalize(sectionTitle);
        knowledgePoint = normalize(knowledgePoint);
    }

    public static CourseKnowledgeSearchFilter allLocal() {
        return new CourseKnowledgeSearchFilter("", "", "", true);
    }

    public static CourseKnowledgeSearchFilter published() {
        return new CourseKnowledgeSearchFilter("", "", "", false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
