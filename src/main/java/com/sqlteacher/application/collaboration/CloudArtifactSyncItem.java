package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record CloudArtifactSyncItem(String operationId, String aggregateType, String aggregateId,
                                    long aggregateVersion, String payloadSha256, String summaryJson,
                                    Instant occurredAt, long serverCursor) {
    public CloudArtifactSyncItem {
        if (operationId == null || operationId.isBlank() || aggregateType == null || aggregateType.isBlank()
                || aggregateId == null || aggregateId.isBlank() || aggregateVersion < 0
                || payloadSha256 == null || !payloadSha256.matches("[0-9a-f]{64}")
                || summaryJson == null || occurredAt == null || serverCursor < 0) {
            throw new IllegalArgumentException("Cloud artifact sync item is invalid");
        }
    }
}
