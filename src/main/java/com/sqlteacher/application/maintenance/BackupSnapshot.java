package com.sqlteacher.application.maintenance;

import java.time.Instant;
import java.util.Objects;

public record BackupSnapshot(
    String id,
    Instant createdAt,
    long sizeBytes,
    boolean automatic
) {
    public BackupSnapshot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
