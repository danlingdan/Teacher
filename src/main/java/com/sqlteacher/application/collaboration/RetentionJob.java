package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record RetentionJob(String id, RetentionCategory category, Instant cutoff, int previewRows,
                           int affectedRows, String status, String backupReference, Instant createdAt,
                           Instant executedAt, Instant restoredAt) { }
