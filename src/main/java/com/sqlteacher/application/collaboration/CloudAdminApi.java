package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;

/** Desktop boundary for administrator operations and retention jobs (v3.4.0 REF-4 port split). */
public interface CloudAdminApi {
    default AdminHealthSummary getAdminHealth(String accessToken) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default List<AdminUserSummary> listAdminUsers(String accessToken) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default AdminUserSummary setUserDisabled(String accessToken, String userId, boolean disabled,
                                             String reasonCode) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default void revokeUserSessions(String accessToken, String userId, String reasonCode) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default AdminAuditPage getAdminAudit(String accessToken, String action, Instant from, Instant to,
                                         int page, int pageSize) {
        throw new UnsupportedOperationException("Administrator operations are unavailable");
    }

    default RetentionPreview previewRetention(String accessToken, RetentionCategory category, Instant cutoff) {
        throw new UnsupportedOperationException("Retention operations are unavailable");
    }

    default RetentionJob executeRetention(String accessToken, String previewId, String confirmationToken,
                                          String backupReference) {
        throw new UnsupportedOperationException("Retention operations are unavailable");
    }

    default RetentionJob restoreRetention(String accessToken, String jobId) {
        throw new UnsupportedOperationException("Retention operations are unavailable");
    }
}
