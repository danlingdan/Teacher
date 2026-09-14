package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PersistentCloudSessionServiceTest {
    /** Fixed reference time: expiry is decided by the injected clock, not the wall clock. */
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void shouldRestoreAnUnexpiredSession() {
        MemoryStore store = new MemoryStore(sessionAt(NOW.plusSeconds(300)));

        var sessions = new PersistentCloudSessionService(store, new NoOpCloudApi(), clockAt(NOW));

        assertTrue(sessions.current().isPresent());
        assertEquals("user-1", sessions.current().orElseThrow().user().id());
    }

    @Test
    void shouldKeepAnExpiredSessionAvailableForRefresh() {
        MemoryStore store = new MemoryStore(sessionAt(NOW.minusSeconds(1)));

        var sessions = new PersistentCloudSessionService(store, new NoOpCloudApi(), clockAt(NOW));

        assertFalse(sessions.current().isPresent());
        assertFalse(store.cleared);
    }

    @Test
    void shouldPersistSignInAndClearSignOut() {
        MemoryStore store = new MemoryStore(null);
        var sessions = new PersistentCloudSessionService(store, new NoOpCloudApi(), clockAt(NOW));

        sessions.signIn(sessionAt(NOW.plusSeconds(300)));
        sessions.signOut();

        assertEquals(1, store.saved);
        assertTrue(store.cleared);
    }

    @Test
    void shouldRotateAndPersistAnExpiringSession() {
        var base = sessionAt(NOW.plusSeconds(30));
        var expiring = new CloudAuthenticationService.Session(base.accessToken(), base.expiresAt(),
            base.user(), "old-refresh");
        var rotated = new CloudAuthenticationService.Session("rotated-access", NOW.plusSeconds(3_600),
            expiring.user(), "rotated-refresh");
        MemoryStore store = new MemoryStore(expiring);
        var sessions = new PersistentCloudSessionService(store, new RefreshingCloudApi(rotated), clockAt(NOW));

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
            NOW.plusSeconds(300),
            sessionAt(NOW.plusSeconds(300)).user(), "secret-refresh");

        store.save(session);

        assertTrue(Files.exists(file));
        String encrypted = Files.readString(file);
        assertFalse(encrypted.contains("secret-access"));
        assertFalse(encrypted.contains("secret-refresh"));
        assertEquals(session, store.load().orElseThrow());
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
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

    private static class NoOpCloudApi implements com.sqlteacher.application.collaboration.CloudAuthApi {
        @Override public CloudAuthenticationService.Session login(String email, char[] password) { throw unsupported(); }
        @Override public CloudAuthenticationService.Session register(String email, String displayName, char[] password) { throw unsupported(); }
        @Override public CloudAuthenticationService.Session refresh(String refreshToken) { throw unsupported(); }
        @Override public void logout(String accessToken) { throw unsupported(); }
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
