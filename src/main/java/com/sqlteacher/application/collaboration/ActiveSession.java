package com.sqlteacher.application.collaboration;

import java.time.Instant;

/** A visible active session for an account; the id is the hashed access token, never the raw token. */
public record ActiveSession(String id, String deviceLabel, Instant createdAt, Instant lastSeenAt) {
    public ActiveSession {
        id = id == null ? "" : id;
        deviceLabel = deviceLabel == null || deviceLabel.isBlank() ? "桌面设备" : deviceLabel.strip();
    }
}
