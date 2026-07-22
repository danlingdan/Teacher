package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudAuthenticationService;

import java.util.Optional;

/** Persists a cloud session only through an OS-protected implementation. */
interface CloudSessionStore {
    Optional<CloudAuthenticationService.Session> load();

    void save(CloudAuthenticationService.Session session);

    void clear();
}
