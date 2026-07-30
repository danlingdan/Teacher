package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.*;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.learning.InterventionStatus;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultInterventionServiceTest {
    @TempDir Path tempDir;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void shouldBuildAuthorizedQueueAndPersistResolution() {
        Path database = initialize();
        var sessions = new InMemoryCloudSessionService();
        var user = new AuthenticatedUser("teacher-1", "teacher@example.com", "Teacher", Set.of(UserRole.TEACHER));
        sessions.signIn(new CloudAuthenticationService.Session("token", NOW.plusSeconds(3600), user));
        var service = new DefaultInterventionService(new StubApi(), sessions, database,
            Clock.fixed(NOW, ZoneOffset.UTC));

        var items = service.refreshAuthorized();
        assertEquals(1, items.size());
        assertEquals("Student", items.getFirst().studentDisplayName());

        service.updateStatus(items.getFirst().id(), InterventionStatus.RESOLVED);
        assertTrue(service.refreshAuthorized().isEmpty());
    }

    @Test
    void shouldEscapeCsvFormulaFields() {
        Path database = initialize();
        var sessions = new InMemoryCloudSessionService();
        sessions.signIn(new CloudAuthenticationService.Session("token", NOW.plusSeconds(3600),
            new AuthenticatedUser("teacher-1", "t@example.com", "Teacher", Set.of(UserRole.TEACHER))));
        var service = new DefaultInterventionService(new StubApi(), sessions, database,
            Clock.fixed(NOW, ZoneOffset.UTC));
        String csv = service.exportCsv(service.refreshAuthorized());
        assertTrue(csv.contains("\"'=Class\""));
    }

    private Path initialize() {
        Path app = tempDir.resolve("app.db");
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration("SQLTeacher", tempDir,
            new DatabaseConfiguration(app, tempDir.resolve("demo.db")),
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1),
                Duration.ofSeconds(1), "test"))).initialize();
        return app;
    }

    private static final class StubApi implements CloudApiClient {
        @Override public List<ClassroomService.Classroom> listClasses(String token) {
            return List.of(new ClassroomService.Classroom("class-1", "=Class", NOW.minusSeconds(100),
                List.of(new ClassroomService.Member("teacher-1", UserRole.TEACHER))));
        }
        @Override public List<ClassAssignment> listAssignments(String token, String classroomId) {
            return List.of(new ClassAssignment("assignment-1", classroomId, "exercise-1", "Task",
                NOW.minusSeconds(1000), AssignmentStatus.PUBLISHED, NOW.minusSeconds(10), NOW.minusSeconds(1000)));
        }
        @Override public AssignmentAnalyticsReport getAssignmentAnalytics(String token, String classroomId,
                String assignmentId, AssignmentAnalyticsFilter filter) {
            return new AssignmentAnalyticsReport(classroomId, assignmentId, 1, 0, 0, 0, 0, 0, List.of(),
                List.of(new AssignmentAnalyticsRow("student-1", "s@example.com", "Student",
                    AssignmentStudentStatus.NOT_SUBMITTED, 0, 0, null)), 0, 200, 1, NOW);
        }
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
