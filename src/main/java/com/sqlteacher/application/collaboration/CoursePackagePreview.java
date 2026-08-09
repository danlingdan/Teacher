package com.sqlteacher.application.collaboration;

public record CoursePackagePreview(
    String packageId,
    String courseTitle,
    String courseVersion,
    String license,
    String contentSha256,
    int sections,
    int knowledgePoints,
    int exercises,
    Conflict conflict
) {
    public CoursePackagePreview {
        if (packageId == null || packageId.isBlank() || courseTitle == null || courseTitle.isBlank()
                || courseVersion == null || courseVersion.isBlank() || license == null || license.isBlank()
                || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")
                || sections < 0 || knowledgePoints < 0 || exercises < 0 || conflict == null) {
            throw new IllegalArgumentException("Course package preview is invalid");
        }
    }

    public enum Conflict { NONE, SAME_CONTENT, VERSION_CONFLICT }
}
