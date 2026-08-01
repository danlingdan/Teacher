package com.sqlteacher.application.collaboration;

import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.ObjectivePrerequisite;
import com.sqlteacher.application.planning.ObjectiveResourceLink;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanSnapshot;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanActionStateRecord;
import com.sqlteacher.application.planning.ObjectiveClassSummary;
import com.sqlteacher.application.planning.ObjectiveInterventionDraft;
import com.sqlteacher.application.planning.PlanningHealthSummary;

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

    default CourseCatalog createCourse(String accessToken, String name, String description) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default List<CourseCatalog> listCourses(String accessToken) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default CourseCatalog updateCourse(String accessToken, String courseId, String name, String description,
                                       ContentStatus status, long expectedVersion) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default CourseSection createCourseSection(String accessToken, String courseId, String name, int sortOrder) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default KnowledgePoint createKnowledgePoint(String accessToken, String courseId, String sectionId,
                                                String name, String description, int sortOrder) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default List<CourseSection> listCourseSections(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default CourseSection updateCourseSection(String accessToken, String courseId, String sectionId, String name,
                                              int sortOrder, ContentStatus status, long expectedVersion) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default List<KnowledgePoint> listKnowledgePoints(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default KnowledgePoint updateKnowledgePoint(String accessToken, String courseId, String knowledgePointId,
                                                String sectionId, String name, String description, int sortOrder,
                                                ContentStatus status, long expectedVersion) {
        throw new UnsupportedOperationException("Course content is unavailable");
    }

    default SharedExerciseVersion publishSharedExercise(String accessToken, String courseId, String exerciseId,
                                                        String title, String prompt, String datasetVersion,
                                                        String evaluationRule, List<String> knowledgePointIds,
                                                        String operationId) {
        throw new UnsupportedOperationException("Shared exercises are unavailable");
    }

    default List<SharedExerciseVersion> listSharedExercises(String accessToken, String courseId,
                                                            String knowledgePointId) {
        throw new UnsupportedOperationException("Shared exercises are unavailable");
    }

    default SharedExerciseVersion setSharedExerciseStatus(String accessToken, String courseId, String exerciseId,
                                                          ContentStatus status) {
        throw new UnsupportedOperationException("Shared exercises are unavailable");
    }

    default ClassAssignment createAssignmentFromVersion(String accessToken, String classroomId,
                                                        String exerciseVersionId, String title,
                                                        String description, Instant dueAt, String operationId) {
        throw new UnsupportedOperationException("Versioned assignment content is unavailable");
    }

    default AssignmentContentSnapshot getAssignmentContentSnapshot(String accessToken, String classroomId,
                                                                   String assignmentId) {
        throw new UnsupportedOperationException("Assignment snapshots are unavailable");
    }

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

    default String exportCourseBundle(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Course export is unavailable");
    }

    default CourseBundleImportResult importCourseBundle(String accessToken, String bundleJson, String operationId) {
        throw new UnsupportedOperationException("Course import is unavailable");
    }

    default CloudKnowledgeArticle publishCloudKnowledge(String accessToken, String courseId, String sectionId,
                                                        String title, String content, String visibility) {
        throw new UnsupportedOperationException("Cloud knowledge is unavailable");
    }

    default List<CloudKnowledgeArticle> listCloudKnowledge(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Cloud knowledge is unavailable");
    }

    default List<CloudKnowledgeSearchHit> searchCloudKnowledge(String accessToken, String courseId, String query,
                                                               int limit) {
        throw new UnsupportedOperationException("Cloud knowledge search is unavailable");
    }

    default CourseObjective createCourseObjective(String accessToken, String courseId, String title,
                                                   String description, String completionCriteria, int sortOrder) {
        throw new UnsupportedOperationException("Course objectives are unavailable");
    }

    default List<CourseObjective> listCourseObjectives(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Course objectives are unavailable");
    }

    default CourseObjective updateCourseObjective(String accessToken, String courseId, String objectiveId,
                                                   String title, String description, String completionCriteria,
                                                   int sortOrder, ContentStatus status, long expectedVersion) {
        throw new UnsupportedOperationException("Course objectives are unavailable");
    }

    default ObjectivePrerequisite addObjectivePrerequisite(String accessToken, String courseId,
                                                            String objectiveId, String prerequisiteObjectiveId) {
        throw new UnsupportedOperationException("Objective prerequisites are unavailable");
    }

    default ObjectiveResourceLink addObjectiveResource(String accessToken, String courseId, String objectiveId,
                                                        ObjectiveResourceType resourceType, String resourceId) {
        throw new UnsupportedOperationException("Objective resources are unavailable");
    }

    default StudyPlanSnapshot getStudyPlan(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Study planning is unavailable");
    }

    default StudyPlanActionStateRecord updateStudyPlanAction(String accessToken, String courseId, String actionId,
                                                              StudyPlanActionState state, long expectedVersion,
                                                              String operationId) {
        throw new UnsupportedOperationException("Study plan synchronization is unavailable");
    }

    default List<ObjectiveClassSummary> getObjectiveClassSummary(String accessToken, String courseId,
                                                                  String classroomId) {
        throw new UnsupportedOperationException("Objective teaching orchestration is unavailable");
    }

    default ObjectiveInterventionDraft createObjectiveInterventionDraft(String accessToken, String courseId,
                                                                          String classroomId, String objectiveId,
                                                                          String reasonCode, String action) {
        throw new UnsupportedOperationException("Objective interventions are unavailable");
    }

    default ObjectiveInterventionDraft confirmObjectiveInterventionDraft(String accessToken, String courseId,
                                                                           String draftId,
                                                                           String confirmationToken) {
        throw new UnsupportedOperationException("Objective interventions are unavailable");
    }

    default PlanningHealthSummary getPlanningHealth(String accessToken) {
        throw new UnsupportedOperationException("Planning operations health is unavailable");
    }
}
