package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.*;
import com.sqlteacher.application.learning.*;
import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.PlanSyncOperation;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanActionStateRecord;
import com.sqlteacher.application.planning.StudyPlanCache;
import com.sqlteacher.application.planning.StudyPlanRefresh;
import com.sqlteacher.application.planning.StudyPlanSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStudentLearningQueueServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldMergeAssignmentsAndFeedbackAheadOfLocalSuggestions() {
        var diagnosis = new StubDiagnosis();
        var sessions = signInStudent();
        var api = new StubApi();
        var service = new DefaultStudentLearningQueueService(diagnosis, api, sessions, FIXED_CLOCK);

        StudentLearningQueue queue = service.refresh();

        assertTrue(queue.cloudAvailable());
        assertEquals(LearningActionType.COMPLETE_ASSIGNMENT, queue.items().getFirst().action().type());
        assertTrue(queue.items().stream().anyMatch(item -> item.action().type() == LearningActionType.REVIEW_FEEDBACK));
        assertTrue(queue.items().stream().anyMatch(item -> item.action().type() == LearningActionType.RETRY_EXERCISE));
    }

    @Test
    void shouldDegradeToLocalQueueWhenCloudFails() {
        var diagnosis = new StubDiagnosis();
        var sessions = signInStudent();
        var api = new StubApi(); api.fail = true;
        var service = new DefaultStudentLearningQueueService(diagnosis, api, sessions, FIXED_CLOCK);

        StudentLearningQueue queue = service.refresh();

        assertFalse(queue.cloudAvailable());
        assertEquals(1, queue.items().size());
    }

    @Test
    void shouldSkipRetryableOperationFailureWithoutDegradingRefresh() {
        var diagnosis = new StubDiagnosis();
        var sessions = signInStudent();
        var api = new StubApi();
        api.syncFailure = new CloudApiRequestException(503, "CLOUD_THROTTLED", "server unavailable");
        var planCache = new StubPlanCache(
            new PlanSyncOperation("op-1", "course-1", "action-1", StudyPlanActionState.STARTED, 1, 0),
            new PlanSyncOperation("op-2", "course-1", "action-2", StudyPlanActionState.COMPLETED, 1, 0));
        var service = new DefaultStudentLearningQueueService(diagnosis, api, sessions, planCache, FIXED_CLOCK);

        StudentLearningQueue queue = service.refresh();

        assertTrue(queue.cloudAvailable());
        assertTrue(planCache.delivered.contains("op-2"));
        assertEquals("CLOUD_THROTTLED:true", planCache.failed.get("op-1"));
        assertFalse(planCache.delivered.contains("op-1"));
    }

    @Test
    void shouldDegradeWhenCloudRejectsPlanSyncAuthentication() {
        var diagnosis = new StubDiagnosis();
        var sessions = signInStudent();
        var api = new StubApi();
        api.syncFailure = new CloudApiRequestException(401, "CLOUD_AUTH_FAILED", "token rejected");
        var planCache = new StubPlanCache(
            new PlanSyncOperation("op-1", "course-1", "action-1", StudyPlanActionState.STARTED, 1, 0));
        var service = new DefaultStudentLearningQueueService(diagnosis, api, sessions, planCache, FIXED_CLOCK);

        StudentLearningQueue queue = service.refresh();

        assertFalse(queue.cloudAvailable());
        assertEquals("CLOUD_AUTH_FAILED:false", planCache.failed.get("op-1"));
        assertEquals(1, queue.items().size());
    }

    @Test
    void shouldTolerateDuplicateKnowledgePointIdsWhenGroundingPlan() {
        var diagnosis = new StubDiagnosis();
        var sessions = signInStudent();
        var api = new StubApi();
        api.courses = List.of(new CourseCatalog("course-1", "Course", "", ContentStatus.ACTIVE, 1,
            "teacher-1", NOW, NOW));
        api.knowledgePoints = List.of(
            new KnowledgePoint("kp-1", "course-1", null, "Knowledge A", "", 0, ContentStatus.ACTIVE, 1, NOW, NOW),
            new KnowledgePoint("kp-1", "course-1", null, "Knowledge B", "", 1, ContentStatus.ACTIVE, 1, NOW, NOW));
        api.plan = new StudyPlanSnapshot("student-1", "course-1", "test",
            NOW.minusSeconds(60), NOW.plusSeconds(3600), List.of());
        var service = new DefaultStudentLearningQueueService(diagnosis, api, sessions, FIXED_CLOCK);

        StudentLearningQueue queue = assertDoesNotThrow(service::refresh);

        assertTrue(queue.cloudAvailable());
    }

    private static InMemoryCloudSessionService signInStudent() {
        var sessions = new InMemoryCloudSessionService(FIXED_CLOCK);
        sessions.signIn(new CloudAuthenticationService.Session("token", NOW.plusSeconds(3600),
            new AuthenticatedUser("student-1", "s@example.com", "Student", Set.of(UserRole.STUDENT))));
        return sessions;
    }

    private static final class StubDiagnosis implements LearningDiagnosisService {
        private final List<String> dismissed = new ArrayList<>();
        @Override public LearningDashboard refresh() {
            LearningAction action = new LearningAction("local", LearningActionType.RETRY_EXERCISE,
                "重练", "确定性建议", "query-01", "基础查询", DiagnosisReasonCode.REPEATED_FAILURE,
                75, NOW.minusSeconds(100), false);
            return new LearningDashboard("student-1", List.of(), List.of(action), NOW, Duration.ZERO, "test");
        }
        @Override public void dismissAction(String id) { dismissed.add(id); }
        @Override public void restoreAction(String id) { dismissed.remove(id); }
        @Override public boolean isActionDismissed(String id) { return dismissed.contains(id); }
        @Override public String exportCsv() { return ""; }
    }

    private static final class StubPlanCache implements StudyPlanCache {
        private final List<PlanSyncOperation> operations;
        private final Set<String> delivered = new java.util.HashSet<>();
        private final Map<String, String> failed = new LinkedHashMap<>();

        StubPlanCache(PlanSyncOperation... operations) {
            this.operations = List.of(operations);
        }

        @Override public void saveObjectives(String courseId, List<CourseObjective> objectives) { }
        @Override public StudyPlanRefresh save(StudyPlanSnapshot snapshot) { throw unsupported(); }
        @Override public List<StudyPlanSnapshot> currentPlans() { return List.of(); }
        @Override public PlanSyncOperation updateAction(String courseId, String actionId, StudyPlanActionState state) {
            throw unsupported();
        }
        @Override public int pendingOperations() { return operations.size(); }
        @Override public List<PlanSyncOperation> pending() { return operations; }
        @Override public void markDelivered(String operationId, String actionId, long serverVersion) {
            delivered.add(operationId);
        }
        @Override public void markFailed(String operationId, String errorCode, boolean retryable) {
            failed.put(operationId, errorCode + ":" + retryable);
        }
        private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
    }

    private static final class StubApi implements CloudApiClient {
        private boolean fail;
        private RuntimeException syncFailure;
        private List<CourseCatalog> courses = List.of();
        private List<KnowledgePoint> knowledgePoints = List.of();
        private StudyPlanSnapshot plan;

        @Override public List<ClassroomService.Classroom> listClasses(String token) {
            if (fail) throw new IllegalStateException("offline");
            return List.of(new ClassroomService.Classroom("class-1", "Class", NOW.minusSeconds(100),
                List.of(new ClassroomService.Member("student-1", UserRole.STUDENT))));
        }
        @Override public List<ClassAssignment> listAssignments(String token, String classroomId) {
            return List.of(new ClassAssignment("assignment-1", classroomId, "query-01", "Task",
                NOW.minusSeconds(1000), AssignmentStatus.PUBLISHED, NOW.plusSeconds(3600), NOW.minusSeconds(50)));
        }
        @Override public List<AssignmentSubmission> listOwnAssignmentSubmissions(String token,String c,String a){return List.of();}
        @Override public List<CloudNotification> listNotifications(String token,int page,int size) {
            return List.of(new CloudNotification("notice-1", NotificationType.FEEDBACK_PUBLISHED,
                "ASSIGNMENT", "assignment-1", "查看反馈", "教师已发布反馈", null, NOW.minusSeconds(20)));
        }
        @Override public List<CourseCatalog> listCourses(String token) { return courses; }
        @Override public List<KnowledgePoint> listKnowledgePoints(String token, String courseId) {
            return knowledgePoints;
        }
        @Override public List<CourseObjective> listCourseObjectives(String token, String courseId) { return List.of(); }
        @Override public StudyPlanSnapshot getStudyPlan(String token, String courseId) { return plan; }
        @Override public StudyPlanActionStateRecord updateStudyPlanAction(String token, String courseId,
                String actionId, StudyPlanActionState state, long expectedVersion, String operationId) {
            if (syncFailure != null && "op-1".equals(operationId)) throw syncFailure;
            return new StudyPlanActionStateRecord("student-1", courseId, actionId, state, expectedVersion + 1, NOW);
        }
        @Override public CloudNotification markNotificationRead(String token,String id){throw unsupported();}
        @Override public CloudAuthenticationService.Session login(String e,char[] p){throw unsupported();}
        @Override public CloudAuthenticationService.Session register(String e,String n,char[] p){throw unsupported();}
        @Override public CloudAuthenticationService.Session refresh(String r){throw unsupported();}
        @Override public void logout(String t){throw unsupported();}
        @Override public ClassroomService.Classroom createClass(String t,String n){throw unsupported();}
        @Override public ClassroomService.Classroom addClassMember(String t,String c,String e,UserRole r){throw unsupported();}
        @Override public ClassAssignment createAssignment(String t,String c,String e,String n){throw unsupported();}
        @Override public ClassAssignment changeAssignmentStatus(String t,String c,String a,AssignmentStatus s){throw unsupported();}
        @Override public ClassAssignment setAssignmentDueAt(String t,String c,String a,Instant d){throw unsupported();}
        @Override public ClassAssignment updateAssignment(String t,String c,String a,String n,Instant d){throw unsupported();}
        @Override public ClassLearningSummary getClassLearningSummary(String t,String c){throw unsupported();}
        @Override public String exportClassLearningCsv(String t,String c){throw unsupported();}
        @Override public int uploadSyncItems(String t,List<CloudSyncItem> i){throw unsupported();}
        @Override public List<CloudSyncItem> downloadSyncItems(String t,long v){throw unsupported();}
        private static UnsupportedOperationException unsupported(){return new UnsupportedOperationException();}
    }
}
