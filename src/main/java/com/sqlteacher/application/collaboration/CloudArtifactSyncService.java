package com.sqlteacher.application.collaboration;

import java.time.Instant;

public interface CloudArtifactSyncService {
    void enqueue(CloudArtifactSyncItem item);

    SyncReport synchronize();

    record SyncReport(int uploaded, int downloaded, int conflicts, long cursor, Instant completedAt) {
        public SyncReport {
            if (uploaded < 0 || downloaded < 0 || conflicts < 0 || cursor < 0 || completedAt == null) {
                throw new IllegalArgumentException("Cloud artifact sync report is invalid");
            }
        }
    }
}
