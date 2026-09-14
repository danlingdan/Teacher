package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Desktop boundary for cloud classrooms, members, assignments, submissions, feedback, and notifications (v3.4.0 REF-4 port split). */
public interface CloudClassroomApi {
    List<ClassroomService.Classroom> listClasses(String accessToken);

    /** Lists one classroom's members with contact details for the class teacher. */
    List<ClassroomService.RosterMember> listClassRoster(String accessToken, String classroomId);

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

    ClassLearningSummary getClassLearningSummary(String accessToken, String classroomId);

    String exportClassLearningCsv(String accessToken, String classroomId);

    default SubmissionFeedback saveSubmissionFeedback(String accessToken, String classroomId, String assignmentId,
                                                       String submissionId, FeedbackStatus status, String comment,
                                                       List<String> knowledgePointIds, long expectedVersion,
                                                       String operationId) {
        throw new UnsupportedOperationException("Submission feedback is unavailable");
    }

    default List<SubmissionFeedback> listSubmissionFeedback(String accessToken, String classroomId,
                                                            String assignmentId) {
        throw new UnsupportedOperationException("Submission feedback is unavailable");
    }

    default FeedbackDraft draftSubmissionFeedback(String accessToken, String classroomId, String assignmentId,
                                                   String submissionId) {
        throw new UnsupportedOperationException("Feedback drafts are unavailable");
    }

    default List<KnowledgeMastery> getKnowledgeMastery(String accessToken, String classroomId, String studentUserId) {
        throw new UnsupportedOperationException("Knowledge mastery is unavailable");
    }

    default List<CloudNotification> listNotifications(String accessToken, int page, int pageSize) {
        throw new UnsupportedOperationException("Notifications are unavailable");
    }

    default CloudNotification markNotificationRead(String accessToken, String notificationId) {
        throw new UnsupportedOperationException("Notifications are unavailable");
    }

    /**
     * W6.2 batch read: per-assignment PASSED status of the current user in one classroom.
     * Servers without the capability throw; callers degrade to per-assignment queries.
     */
    default Map<String, Boolean> listOwnAssignmentPassedStatuses(String accessToken, String classroomId) {
        throw new UnsupportedOperationException("Batch submission status is unavailable");
    }
}
