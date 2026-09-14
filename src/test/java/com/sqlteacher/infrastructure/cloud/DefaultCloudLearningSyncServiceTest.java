package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.*;
import com.sqlteacher.application.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class DefaultCloudLearningSyncServiceTest {
    @TempDir Path stateDirectory;

    @Test
    void shouldUploadOnlyEventsOwnedByCurrentAccount() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        var events = List.of(
            event(1, now, LearningEventOwnerProvider.GUEST_OWNER),
            event(2, now.plusSeconds(1), "user-1"),
            event(3, now.plusSeconds(2), "another-user")
        );
        var api = new RecordingCloudSyncApi();
        var sessions = new InMemoryCloudSessionService();
        sessions.signIn(new CloudAuthenticationService.Session(
            "token",
            Instant.now().plusSeconds(3_600),
            new AuthenticatedUser("user-1", "user@example.com", "User", Set.of(UserRole.STUDENT))
        ));
        LearningEventQueryService query = new StubQuery(events);
        var service = new DefaultCloudLearningSyncService(api, sessions, query, ignored -> { }, stateDirectory);

        var result = service.synchronize();

        assertEquals(1, result.uploaded());
        assertEquals(1, api.uploaded.size());
        assertEquals("SQL_EXECUTION", api.uploaded.getFirst().type());
        assertEquals(true, api.uploaded.getFirst().id().endsWith(":2"));
    }

    @Test
    void shouldRetryTransientFailuresAndExposeSuccessStatus() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        var api = new RecordingCloudSyncApi();
        api.failuresRemaining = 2;
        var sessions = new InMemoryCloudSessionService();
        sessions.signIn(new CloudAuthenticationService.Session(
            "token", Instant.now().plusSeconds(3_600),
            new AuthenticatedUser("user-1", "user@example.com", "User", Set.of(UserRole.STUDENT))
        ));
        var service = new DefaultCloudLearningSyncService(api, sessions,
            new StubQuery(List.of(event(1, now, "user-1"))), ignored -> { }, stateDirectory);

        var result = service.synchronize();

        assertEquals(1, result.uploaded());
        assertEquals(3, api.uploadAttempts);
        assertEquals(CloudLearningSyncService.SyncStatus.State.SUCCEEDED, service.status().state());
        assertEquals(3, service.status().attempt());
        assertEquals(0, service.status().pending());
    }

    private static LearningEventQueryService.QueriedLearningEvent event(long id, Instant occurredAt, String owner) {
        return new LearningEventQueryService.QueriedLearningEvent(
            id,
            LearningEventType.SQL_EXECUTION,
            occurredAt,
            "demo",
            true,
            Map.of(LearningEventOwnerProvider.OWNER_ATTRIBUTE, owner),
            occurredAt
        );
    }

    private record StubQuery(List<LearningEventQueryService.QueriedLearningEvent> events)
        implements LearningEventQueryService {
        @Override
        public List<QueriedLearningEvent> queryEventsByType(LearningEventType type, Instant start, Instant end) {
            return type == LearningEventType.SQL_EXECUTION ? events : List.of();
        }

        @Override
        public List<QueriedLearningEvent> queryEventsByConnection(String connectionId, Instant start, Instant end) {
            return List.of();
        }

        @Override
        public EventStatistics getEventStatistics(Instant start, Instant end) {
            return new EventStatistics(0, 0, 0, Map.of(), Map.of());
        }
    }

    private static final class RecordingCloudSyncApi implements CloudSyncApi {
        private final List<CloudSyncItem> uploaded = new ArrayList<>();
        private int failuresRemaining;
        private int uploadAttempts;

        @Override public int uploadSyncItems(String token, List<CloudSyncItem> items) {
            uploadAttempts++;
            if (failuresRemaining-- > 0) throw new IllegalStateException("temporary network failure");
            uploaded.addAll(items);
            return items.size();
        }

        @Override public List<CloudSyncItem> downloadSyncItems(String token, long afterVersion) { return List.of(); }
    }
}
