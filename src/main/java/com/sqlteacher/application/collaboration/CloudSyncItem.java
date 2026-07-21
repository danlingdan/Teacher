package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.Objects;

/** A user-scoped, idempotent learning record transferred to the cloud. */
public record CloudSyncItem(String id, String type, String payloadJson, Instant occurredAt, long version) {
    public CloudSyncItem {
        if (id == null || id.isBlank() || type == null || type.isBlank()) {
            throw new IllegalArgumentException("Sync item id and type must not be blank");
        }
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }
}
