package com.sqlteacher.application.collaboration;

import java.util.Optional;

/** The desktop keeps only the active session in memory; no token is persisted to app.db. */
public interface CloudSessionService {
    Optional<CloudAuthenticationService.Session> current();

    void signIn(CloudAuthenticationService.Session session);

    void signOut();
}
