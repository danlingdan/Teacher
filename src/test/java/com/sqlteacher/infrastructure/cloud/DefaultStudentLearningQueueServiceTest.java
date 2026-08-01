package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.*;
import com.sqlteacher.application.learning.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStudentLearningQueueServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldMergeAssignmentsAndFeedbackAheadOfLocalSuggestions() {
        var diagnosis = new StubDiagnosis();
        var sessions = new InMemoryCloudSessionService(FIXED_CLOCK);
        sessions.signIn(new CloudAuthenticationService.Session("token", NOW.plusSeconds(3600),
            new AuthenticatedUser("student-1", "s@example.com", "Student", Set.of(UserRole.STUDENT))));
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
        var sessions = new InMemoryCloudSessionService(FIXED_CLOCK);
        sessions.signIn(new CloudAuthenticationService.Session("token", NOW.plusSeconds(3600),
            new AuthenticatedUser("student-1", "s@example.com", "Student", Set.of(UserRole.STUDENT))));
        var api = new StubApi(); api.fail = true;
        var service = new DefaultStudentLearningQueueService(diagnosis, api, sessions, FIXED_CLOCK);

        StudentLearningQueue queue = service.refresh();

        assertFalse(queue.cloudAvailable());
        assertEquals(1, queue.items().size());
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

    private static final class StubApi implements CloudApiClient {
        private boolean fail;
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
        @Override public List<CourseCatalog> listCourses(String token) { return List.of(); }
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
