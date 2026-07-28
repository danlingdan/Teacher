package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record AdminAuditEntry(String id, String actorUserId, String action, String targetType,
                              String targetId, String result, String reasonCode, String correlationId,
                              Instant createdAt) { }
