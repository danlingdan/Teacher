package com.sqlteacher.application.collaboration;

import java.time.Instant;

public interface CloudLearningSyncService {
    SyncResult synchronize();

    SyncStatus status();

    record SyncResult(int uploaded, int downloaded, long cursor) { }

    record SyncStatus(State state, int pending, int attempt, Instant lastSuccessAt,
                      Instant nextRetryAt, String errorCode) {
        public SyncStatus {
            if (state == null || pending < 0 || attempt < 0) throw new IllegalArgumentException("Invalid sync status");
        }

        public static SyncStatus idle() { return new SyncStatus(State.IDLE, 0, 0, null, null, null); }

        public enum State { IDLE, SYNCING, SUCCEEDED, RETRY_WAIT, FAILED }
    }
}
