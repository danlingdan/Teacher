package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record RetentionPreview(String id, RetentionCategory category, Instant cutoff, int affectedRows,
                               Instant expiresAt, String confirmationToken) { }
