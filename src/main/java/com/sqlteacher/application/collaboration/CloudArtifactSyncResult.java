package com.sqlteacher.application.collaboration;

public record CloudArtifactSyncResult(String operationId, Status status, long serverCursor,
                                      long currentVersion, String conflictCode) {
    public CloudArtifactSyncResult {
        if (operationId == null || operationId.isBlank() || status == null || serverCursor < 0
                || currentVersion < -1) throw new IllegalArgumentException("Cloud sync result is invalid");
        conflictCode = conflictCode == null ? "" : conflictCode.trim();
    }

    public enum Status { ACCEPTED, DUPLICATE, CONFLICT }
}
