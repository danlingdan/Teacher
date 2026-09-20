package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudSyncPreferences;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** v3.7.0 TFB-C2: the auto-sync loop respects its switches and never disturbs local learning. */
class LearningAutoSyncServiceTest {
    private static final CloudSessionService SIGNED_IN = new StubSessions();

    @Test
    void tickSyncsOnlyWhenEnabledSignedInAndNotPaused() {
        AtomicInteger syncCalls = new AtomicInteger();
        CloudLearningSyncService sync = new CloudLearningSyncService() {
            @Override public SyncResult synchronize() {
                syncCalls.incrementAndGet();
                return new SyncResult(0, 0, 0);
            }
            @Override public SyncStatus status() { return SyncStatus.idle(); }
        };
        RecordingPreferences preferences = new RecordingPreferences();
        LearningAutoSyncService service = new LearningAutoSyncService(sync, SIGNED_IN, preferences);

        preferences.autoSync = false;
        service.tick();
        assertEquals(0, syncCalls.get());

        preferences.autoSync = true;
        preferences.paused = true;
        service.tick();
        assertEquals(0, syncCalls.get());

        preferences.paused = false;
        service.tick();
        assertEquals(1, syncCalls.get());
    }

    @Test
    void signedOutSessionNeverTriggersSync() {
        AtomicInteger syncCalls = new AtomicInteger();
        CloudLearningSyncService sync = new CloudLearningSyncService() {
            @Override public SyncResult synchronize() {
                syncCalls.incrementAndGet();
                return new SyncResult(0, 0, 0);
            }
            @Override public SyncStatus status() { return SyncStatus.idle(); }
        };
        LearningAutoSyncService service =
            new LearningAutoSyncService(sync, new StubSessions(false), new RecordingPreferences());

        service.tick();

        assertEquals(0, syncCalls.get());
    }

    @Test
    void tickSwallowsSyncFailuresSoTheLoopKeepsRunning() {
        CloudLearningSyncService failingSync = new CloudLearningSyncService() {
            @Override public SyncResult synchronize() {
                throw new IllegalStateException("学习记录同步失败");
            }
            @Override public SyncStatus status() { return SyncStatus.idle(); }
        };
        LearningAutoSyncService service =
            new LearningAutoSyncService(failingSync, SIGNED_IN, new RecordingPreferences());
        service.tick();
    }

    private static final class StubSessions implements CloudSessionService {
        private final boolean signedIn;

        StubSessions(boolean signedIn) { this.signedIn = signedIn; }

        StubSessions() { this(true); }

        @Override public Optional<CloudAuthenticationService.Session> current() {
            return signedIn
                ? Optional.of(new CloudAuthenticationService.Session(
                    "token", Instant.now().plusSeconds(600),
                    new AuthenticatedUser("u-1", "u@example.edu", "User", Set.of(UserRole.STUDENT))))
                : Optional.empty();
        }

        @Override public void signIn(CloudAuthenticationService.Session session) { }

        @Override public void signOut() { }
    }

    private static final class RecordingPreferences implements CloudSyncPreferences {
        boolean paused;
        boolean autoSync = true;

        @Override public boolean uploadPaused() { return paused; }
        @Override public void uploadPaused(boolean value) { paused = value; }
        @Override public boolean autoSyncEnabled() { return autoSync; }
        @Override public void autoSyncEnabled(boolean value) { autoSync = value; }
    }
}
