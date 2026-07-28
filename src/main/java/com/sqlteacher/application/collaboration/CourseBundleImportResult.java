package com.sqlteacher.application.collaboration;

public record CourseBundleImportResult(String courseId, int sections, int knowledgePoints, int exercises) {
    public CourseBundleImportResult {
        if (courseId == null || courseId.isBlank() || sections < 0 || knowledgePoints < 0 || exercises < 0) {
            throw new IllegalArgumentException("Course import result is invalid");
        }
    }
}
