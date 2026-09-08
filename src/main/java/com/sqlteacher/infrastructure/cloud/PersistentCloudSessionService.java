package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudApiRequestException;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Restores only a valid session and removes expired or explicitly signed-out sessions. */
public final class PersistentCloudSessionService implements CloudSessionService {
    private final CloudSessionStore store;
    private final CloudApiClient api;
    private CloudAuthenticationService.Session session;

    public PersistentCloudSessionService(CloudSessionStore store, CloudApiClient api) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.session = store.load().orElse(null);
    }

    @Override
    public synchronized Optional<CloudAuthenticationService.Session> current() {
        if (session != null && session.expiresAt().isAfter(Instant.now())) {
            return Optional.of(session);
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<CloudAuthenticationService.Session> refresh() {
        if (session == null || session.refreshToken() == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isAfter(Instant.now().plusSeconds(300))) {
            return Optional.of(session);
        }
        try {
            signIn(api.refresh(session.refreshToken()));
            return Optional.of(session);
        } catch (RuntimeException error) {
            if (isSessionRejected(error)) {
                signOut();
            }
            return Optional.empty();
        }
    }

    /** A rejected refresh must clear the stored session; string matching stays only as a fallback. */
    private static boolean isSessionRejected(RuntimeException error) {
        if (error instanceof CloudApiRequestException request
                && (request.statusCode() == 401 || request.statusCode() == 403)) {
            return true;
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("HTTP 401") || message.contains("HTTP 403");
    }

    @Override
    public synchronized void signIn(CloudAuthenticationService.Session session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        store.save(session);
    }

    @Override
    public synchronized void signOut() {
        session = null;
        store.clear();
    }
}
