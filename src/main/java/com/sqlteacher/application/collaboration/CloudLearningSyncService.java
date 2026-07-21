package com.sqlteacher.application.collaboration;

public interface CloudLearningSyncService {
    SyncResult synchronize();

    record SyncResult(int uploaded, int downloaded, long cursor) { }
}
