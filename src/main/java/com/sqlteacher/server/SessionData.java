package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;

import java.time.Instant;

/**
 * Token bundle issued by the cloud authentication store and rendered into login, registration,
 * and refresh responses (moved out of {@link SqlTeacherCloudServer} in v3.4.0 REF-2).
 */
record SessionData(String token, Instant expiresAt, AuthenticatedUser user, String refreshToken) { }
