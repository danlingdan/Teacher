package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentCloudSessionServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldRestoreAnUnexpiredSession() {
        MemoryStore store = new MemoryStore(sessionAt(Instant.now().plusSeconds(300)));

        var sessions = new PersistentCloudSessionService(store, new NoOpCloudApi());

        assertTrue(sessions.current().isPresent());
        assertEquals("user-1", sessions.current().orElseThrow().user().id());
    }

    @Test
    void shouldKeepAnExpiredSessionAvailableForRefresh() {
        MemoryStore store = new MemoryStore(sessionAt(Instant.now().minusSeconds(1)));

        var sessions = new PersistentCloudSessionService(store, new NoOpCloudApi());

        assertFalse(sessions.current().isPresent());
        assertFalse(store.cleared);
    }

    @Test
    void shouldPersistSignInAndClearSignOut() {
        MemoryStore store = new MemoryStore(null);
        var sessions = new PersistentCloudSessionService(store, new NoOpCloudApi());

        sessions.signIn(sessionAt(Instant.now().plusSeconds(300)));
        sessions.signOut();

        assertEquals(1, store.saved);
        assertTrue(store.cleared);
    }

    @Test
    void shouldRotateAndPersistAnExpiringSession() {
        var base = sessionAt(Instant.now().plusSeconds(30));
        var expiring = new CloudAuthenticationService.Session(base.accessToken(), base.expiresAt(),
            base.user(), "old-refresh");
        var rotated = new CloudAuthenticationService.Session("rotated-access", Instant.now().plusSeconds(3_600),
            expiring.user(), "rotated-refresh");
        MemoryStore store = new MemoryStore(expiring);
        var sessions = new PersistentCloudSessionService(store, new RefreshingCloudApi(rotated));

        var refreshed = sessions.refresh().orElseThrow();

        assertEquals("rotated-access", refreshed.accessToken());
        assertEquals("rotated-refresh", store.session.refreshToken());
        assertEquals(1, store.saved);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldEncryptPersistedSessionWithWindowsDpapi() throws Exception {
        Path file = temporaryDirectory.resolve("cloud-session.dat");
        var store = new WindowsDpapiCloudSessionStore(file);
        var session = new CloudAuthenticationService.Session("secret-access",
            Instant.ofEpochMilli(Instant.now().plusSeconds(300).toEpochMilli()),
            sessionAt(Instant.now().plusSeconds(300)).user(), "secret-refresh");

        store.save(session);

        assertTrue(Files.exists(file));
        String encrypted = Files.readString(file);
        assertFalse(encrypted.contains("secret-access"));
        assertFalse(encrypted.contains("secret-refresh"));
        assertEquals(session, store.load().orElseThrow());
    }

    private static CloudAuthenticationService.Session sessionAt(Instant expiresAt) {
        return new CloudAuthenticationService.Session("access-token", expiresAt,
            new AuthenticatedUser("user-1", "student@example.edu", "Student", Set.of(UserRole.STUDENT)));
    }

    private static final class MemoryStore implements CloudSessionStore {
        private CloudAuthenticationService.Session session;
        private int saved;
        private boolean cleared;

        private MemoryStore(CloudAuthenticationService.Session session) {
            this.session = session;
        }

        @Override public Optional<CloudAuthenticationService.Session> load() { return Optional.ofNullable(session); }
        @Override public void save(CloudAuthenticationService.Session session) { this.session = session; saved++; }
        @Override public void clear() { session = null; cleared = true; }
    }

    private static class NoOpCloudApi implements com.sqlteacher.application.collaboration.CloudApiClient {
        @Override public CloudAuthenticationService.Session login(String email, char[] password) { throw unsupported(); }
        @Override public CloudAuthenticationService.Session register(String email, String displayName, char[] password) { throw unsupported(); }
        @Override public CloudAuthenticationService.Session refresh(String refreshToken) { throw unsupported(); }
        @Override public void logout(String accessToken) { throw unsupported(); }
        @Override public java.util.List<com.sqlteacher.application.collaboration.ClassroomService.Classroom> listClasses(String accessToken) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassroomService.Classroom createClass(String accessToken, String name) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassroomService.Classroom addClassMember(String accessToken, String classroomId, String email, com.sqlteacher.application.collaboration.UserRole role) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment createAssignment(String accessToken, String classroomId, String exerciseId, String title) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment changeAssignmentStatus(String accessToken, String classroomId, String assignmentId, com.sqlteacher.application.collaboration.AssignmentStatus status) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment setAssignmentDueAt(String accessToken, String classroomId, String assignmentId, Instant dueAt) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassAssignment updateAssignment(String accessToken, String classroomId, String assignmentId, String title, Instant dueAt) { throw unsupported(); }
        @Override public java.util.List<com.sqlteacher.application.collaboration.ClassAssignment> listAssignments(String accessToken, String classroomId) { throw unsupported(); }
        @Override public com.sqlteacher.application.collaboration.ClassLearningSummary getClassLearningSummary(String accessToken, String classroomId) { throw unsupported(); }
        @Override public String exportClassLearningCsv(String accessToken, String classroomId) { throw unsupported(); }
        @Override public int uploadSyncItems(String accessToken, java.util.List<com.sqlteacher.application.collaboration.CloudSyncItem> items) { throw unsupported(); }
        @Override public java.util.List<com.sqlteacher.application.collaboration.CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
    }

    private static final class RefreshingCloudApi extends NoOpCloudApi {
        private final CloudAuthenticationService.Session refreshed;

        private RefreshingCloudApi(CloudAuthenticationService.Session refreshed) {
            this.refreshed = refreshed;
        }

        @Override public CloudAuthenticationService.Session refresh(String refreshToken) { return refreshed; }
    }
}
