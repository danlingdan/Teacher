package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSessionService;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryCloudSessionService implements CloudSessionService {
    private final Clock clock;
    private volatile CloudAuthenticationService.Session session;

    public InMemoryCloudSessionService() {
        this(Clock.systemUTC());
    }

    public InMemoryCloudSessionService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<CloudAuthenticationService.Session> current() {
        CloudAuthenticationService.Session current = session;
        if (current != null && current.expiresAt().isAfter(clock.instant())) {
            return Optional.of(current);
        }
        session = null;
        return Optional.empty();
    }

    @Override
    public void signIn(CloudAuthenticationService.Session session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    @Override
    public void signOut() {
        session = null;
    }
}
