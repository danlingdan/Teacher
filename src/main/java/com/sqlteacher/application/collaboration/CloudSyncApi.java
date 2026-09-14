package com.sqlteacher.application.collaboration;

import java.util.List;

/** Desktop boundary for cloud synchronization of learning events and versioned artifacts (v3.4.0 REF-4 port split). */
public interface CloudSyncApi {
    int uploadSyncItems(String accessToken, List<CloudSyncItem> items);

    List<CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion);

    default List<CloudArtifactSyncResult> uploadArtifactSyncItems(String accessToken,
                                                                  List<CloudArtifactSyncItem> items) {
        throw new UnsupportedOperationException("Cloud 2.0 synchronization is unavailable");
    }

    default CloudArtifactSyncPage downloadArtifactSyncItems(String accessToken, long afterCursor) {
        throw new UnsupportedOperationException("Cloud 2.0 synchronization is unavailable");
    }
}
