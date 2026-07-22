package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.Objects;

/** Boundary used by clients and the cloud server; raw passwords never leave this use case. */
public interface CloudAuthenticationService {
    Session register(String email, String displayName, char[] password);

    Session login(String email, char[] password);

    AuthenticatedUser authenticate(String accessToken);

    void logout(String accessToken);

    record Session(String accessToken, Instant expiresAt, AuthenticatedUser user, String refreshToken) {
        public Session(String accessToken, Instant expiresAt, AuthenticatedUser user) {
            this(accessToken, expiresAt, user, null);
        }

        public Session {
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("accessToken must not be blank");
            }
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(user, "user must not be null");
            if (refreshToken != null && refreshToken.isBlank()) {
                throw new IllegalArgumentException("refreshToken must not be blank when provided");
            }
        }
    }
}
