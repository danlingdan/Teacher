package com.sqlteacher.application.collaboration;

import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.ObjectiveClassSummary;
import com.sqlteacher.application.planning.ObjectiveInterventionDraft;
import com.sqlteacher.application.planning.ObjectivePrerequisite;
import com.sqlteacher.application.planning.ObjectiveResourceLink;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.PlanningHealthSummary;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanActionStateRecord;
import com.sqlteacher.application.planning.StudyPlanSnapshot;

import java.time.Instant;
import java.util.List;

/** Desktop boundary for cloud courses, content, shared exercise versions, objectives, and study plans (v3.4.0 REF-4 port split). */
public interface CloudPlanningApi {
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

    default String exportCourseBundle(String accessToken, String courseId) {
        throw new UnsupportedOperationException("Course export is unavailable");
    }

    default CourseBundleImportResult importCourseBundle(String accessToken, String bundleJson, String operationId) {
        throw new UnsupportedOperationException("Course import is unavailable");
    }

    default CoursePackagePreview previewCoursePackage(String accessToken, String packageJson) {
        throw new UnsupportedOperationException("Secure course packages are unavailable");
    }

    default CourseBundleImportResult importCoursePackage(String accessToken, String packageJson, String operationId,
                                                          String expectedSha256, boolean licenseConfirmed) {
        throw new UnsupportedOperationException("Secure course packages are unavailable");
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
