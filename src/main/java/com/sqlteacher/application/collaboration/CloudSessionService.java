package com.sqlteacher.application.collaboration;

import java.util.Optional;

/** The desktop keeps only the active session in memory; no token is persisted to app.db. */
public interface CloudSessionService {
    Optional<CloudAuthenticationService.Session> current();

    /** Attempts to rotate a persisted refresh token without exposing it to callers. */
    default Optional<CloudAuthenticationService.Session> refresh() {
        return current();
    }

    void signIn(CloudAuthenticationService.Session session);

    void signOut();
}
