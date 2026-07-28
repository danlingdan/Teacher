package com.sqlteacher.application.collaboration;

import java.util.List;
import java.time.Instant;

/** Desktop boundary for the authenticated SQLTeacher cloud API. */
public interface CloudApiClient {
    CloudAuthenticationService.Session login(String email, char[] password);

    CloudAuthenticationService.Session register(String email, String displayName, char[] password);

    CloudAuthenticationService.Session refresh(String refreshToken);

    void logout(String accessToken);

    default void logout(String accessToken, String refreshToken) {
        logout(accessToken);
    }

    List<ClassroomService.Classroom> listClasses(String accessToken);

    ClassroomService.Classroom createClass(String accessToken, String name);

    ClassroomService.Classroom addClassMember(String accessToken, String classroomId, String email, UserRole role);

    ClassAssignment createAssignment(String accessToken, String classroomId, String exerciseId, String title);

    default ClassAssignment createAssignment(String accessToken, String classroomId, String exerciseId, String title,
                                              Instant dueAt) {
        if (dueAt != null) throw new UnsupportedOperationException("Assignment due dates are unavailable");
        return createAssignment(accessToken, classroomId, exerciseId, title);
    }

    default ClassAssignment createAssignmentDraft(String accessToken, String classroomId, String exerciseId,
                                                   String title, String description, Instant dueAt) {
        throw new UnsupportedOperationException("Assignment drafts are unavailable");
    }

    default ClassAssignment copyAssignment(String accessToken, String classroomId, String assignmentId,
                                           long expectedVersion) {
        throw new UnsupportedOperationException("Assignment copying is unavailable");
    }

    ClassAssignment changeAssignmentStatus(String accessToken, String classroomId, String assignmentId, AssignmentStatus status);

    default ClassAssignment changeAssignmentStatus(String accessToken, String classroomId, String assignmentId,
                                                    AssignmentStatus status, long expectedVersion) {
        throw new UnsupportedOperationException("Versioned assignment updates are unavailable");
    }

    ClassAssignment setAssignmentDueAt(String accessToken, String classroomId, String assignmentId, Instant dueAt);

    default ClassAssignment setAssignmentDueAt(String accessToken, String classroomId, String assignmentId,
                                               Instant dueAt, long expectedVersion) {
        throw new UnsupportedOperationException("Versioned assignment updates are unavailable");
    }

    ClassAssignment updateAssignment(String accessToken, String classroomId, String assignmentId,
                                     String title, Instant dueAt);

    default ClassAssignment updateAssignment(String accessToken, String classroomId, String assignmentId,
                                             String title, String description, Instant dueAt,
                                             long expectedVersion) {
        throw new UnsupportedOperationException("Versioned assignment updates are unavailable");
    }

    List<ClassAssignment> listAssignments(String accessToken, String classroomId);

    default AssignmentSubmission submitAssignment(String accessToken, String classroomId, String assignmentId,
                                                   AssignmentSubmissionRequest request) {
        throw new UnsupportedOperationException("Assignment submissions are unavailable");
    }

    default List<AssignmentSubmission> listOwnAssignmentSubmissions(String accessToken, String classroomId,
                                                                    String assignmentId) {
        throw new UnsupportedOperationException("Assignment submissions are unavailable");
    }

    default AssignmentAnalyticsReport getAssignmentAnalytics(String accessToken, String classroomId,
                                                              String assignmentId,
                                                              AssignmentAnalyticsFilter filter) {
        throw new UnsupportedOperationException("Assignment analytics are unavailable");
    }

    default String exportAssignmentAnalyticsCsv(String accessToken, String classroomId, String assignmentId,
                                                AssignmentAnalyticsFilter filter) {
        throw new UnsupportedOperationException("Assignment analytics are unavailable");
    }

    default AdminHealthSummary getAdminHealth(String accessToken) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default List<AdminUserSummary> listAdminUsers(String accessToken) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default AdminUserSummary setUserDisabled(String accessToken, String userId, boolean disabled,
                                             String reasonCode) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default void revokeUserSessions(String accessToken, String userId, String reasonCode) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default AdminAuditPage getAdminAudit(String accessToken, String action, Instant from, Instant to,
                                         int page, int pageSize) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default RetentionPreview previewRetention(String accessToken, RetentionCategory category, Instant cutoff) {
        throw new UnsupportedOperationException("Retention operations are unavailable");
    }

    default RetentionJob executeRetention(String accessToken, String previewId, String confirmationToken,
                                          String backupReference) {
        throw new UnsupportedOperationException("Retention operations are unavailable");
    }

    default RetentionJob restoreRetention(String accessToken, String jobId) {
        throw new UnsupportedOperationException("Retention operations are unavailable");
    }

    ClassLearningSummary getClassLearningSummary(String accessToken, String classroomId);

    String exportClassLearningCsv(String accessToken, String classroomId);

    int uploadSyncItems(String accessToken, List<CloudSyncItem> items);

    List<CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion);
}
