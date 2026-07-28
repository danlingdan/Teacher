package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.collaboration.AssignmentSubmission;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRequest;
import com.sqlteacher.application.collaboration.AssignmentSubmissionStatus;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.infrastructure.cloud.JdbcAssignmentDeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JdbcAssignmentDeliveryServiceTest {
    @TempDir Path directory;

    @Test
    void shouldPersistRetryAcrossRestartAndIsolateAccounts() throws Exception {
        Path database = directory.resolve("app.db");
        new SqliteSchemaMigrator().migrate(database);
        MutableSessions sessions = new MutableSessions(session("student-1"));
        FailingSubmissionApi api = new FailingSubmissionApi();
        var service = new JdbcAssignmentDeliveryService(api, sessions, database);

        var queued = service.deliver("class-1", "assignment-1", true, null, Instant.now());

        assertEquals(com.sqlteacher.application.collaboration.AssignmentDeliveryResult.Status.QUEUED,
            queued.status());
        assertEquals(1, service.pendingCount());
        sessions.session = session("student-2");
        assertEquals(0, service.pendingCount());

        sessions.session = session("student-1");
        api.available = true;
        var restarted = new JdbcAssignmentDeliveryService(api, sessions, database);
        var retry = restarted.retryPending();

        assertEquals(1, retry.attempted());
        assertEquals(1, retry.delivered());
        assertEquals(0, retry.remaining());
        assertEquals(queued.operationId(), api.lastRequest.operationId());
        assertEquals(64, api.lastRequest.resultHash().length());
        assertFalse(hasColumn(database, "assignment_submission_queue", "sql"));
    }

    @Test
    void shouldNotQueuePermanentServerRejection() throws Exception {
        Path database = directory.resolve("rejected.db");
        new SqliteSchemaMigrator().migrate(database);
        MutableSessions sessions = new MutableSessions(session("student-1"));
        FailingSubmissionApi api = new FailingSubmissionApi();
        api.available = true;
        api.reject = true;
        var service = new JdbcAssignmentDeliveryService(api, sessions, database);

        var result = service.deliver("class-1", "assignment-1", false, "RESULT_MISMATCH", Instant.now());

        assertEquals(com.sqlteacher.application.collaboration.AssignmentDeliveryResult.Status.REJECTED,
            result.status());
        assertEquals(0, service.pendingCount());
    }

    private CloudAuthenticationService.Session session(String id) {
        return new CloudAuthenticationService.Session("token-" + id, Instant.now().plusSeconds(3600),
            new AuthenticatedUser(id, id + "@example.edu", id, Set.of(UserRole.STUDENT)));
    }

    private boolean hasColumn(Path database, String table, String column) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (rows.next()) if (column.equalsIgnoreCase(rows.getString("name"))) return true;
            return false;
        }
    }

    private static final class MutableSessions implements CloudSessionService {
        private CloudAuthenticationService.Session session;

        private MutableSessions(CloudAuthenticationService.Session session) { this.session = session; }
        @Override public Optional<CloudAuthenticationService.Session> current() { return Optional.ofNullable(session); }
        @Override public void signIn(CloudAuthenticationService.Session value) { session = value; }
        @Override public void signOut() { session = null; }
    }

    private static final class FailingSubmissionApi implements CloudApiClient {
        private boolean available;
        private boolean reject;
        private AssignmentSubmissionRequest lastRequest;

        @Override
        public AssignmentSubmission submitAssignment(String token, String classroomId, String assignmentId,
                                                     AssignmentSubmissionRequest request) {
            lastRequest = request;
            if (!available) throw new IllegalStateException("offline");
            if (reject) throw new com.sqlteacher.application.collaboration.CloudApiRequestException(
                409, "ASSIGNMENT_DEADLINE_PASSED", "deadline passed");
            return new AssignmentSubmission("submission-1", request.operationId(), classroomId, assignmentId,
                "student-1", 1, request.passed() ? AssignmentSubmissionStatus.PASSED
                    : AssignmentSubmissionStatus.FAILED, request.resultHash(), request.errorCode(),
                request.clientCompletedAt(), Instant.now());
        }

        @Override public CloudAuthenticationService.Session login(String email, char[] password) { throw unsupported(); }
        @Override public CloudAuthenticationService.Session register(String email, String name, char[] password) { throw unsupported(); }
        @Override public CloudAuthenticationService.Session refresh(String refreshToken) { throw unsupported(); }
        @Override public void logout(String accessToken) { throw unsupported(); }
        @Override public List<com.sqlteacher.application.collaboration.ClassroomService.Classroom> listClasses(String token) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassroomService.Classroom createClass(String token, String name) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassroomService.Classroom addClassMember(String token, String classroomId, String email, UserRole role) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment createAssignment(String token, String classroomId, String exerciseId, String title) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment changeAssignmentStatus(String token, String classroomId, String assignmentId, com.sqlteacher.application.collaboration.AssignmentStatus status) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment setAssignmentDueAt(String token, String classroomId, String assignmentId, Instant dueAt) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment updateAssignment(String token, String classroomId, String assignmentId, String title, Instant dueAt) { throw unsupported(); }
        @Override public List<com.sqlteacher.application.collaboration.ClassAssignment> listAssignments(String token, String classroomId) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassLearningSummary getClassLearningSummary(String token, String classroomId) { throw unsupported(); }
        @Override public String exportClassLearningCsv(String token, String classroomId) { throw unsupported(); }
        @Override public int uploadSyncItems(String token, List<com.sqlteacher.application.collaboration.CloudSyncItem> items) { throw unsupported(); }
        @Override public List<com.sqlteacher.application.collaboration.CloudSyncItem> downloadSyncItems(String token, long version) { throw unsupported(); }
        private UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
    }
}
