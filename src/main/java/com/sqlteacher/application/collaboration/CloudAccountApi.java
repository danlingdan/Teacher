package com.sqlteacher.application.collaboration;

/** Desktop boundary for the signed-in account's sessions, data export, and deletion (v3.4.0 REF-4 port split). */
public interface CloudAccountApi {
    default java.util.List<ActiveSession> listSessions(String accessToken) {
        throw new UnsupportedOperationException("Session management is unavailable");
    }

    /** Revokes another session by its hashed id; the current session is protected. */
    default void revokeSession(String accessToken, String sessionId) {
        throw new UnsupportedOperationException("Session management is unavailable");
    }

    /** Starts an async export of the caller's own cloud data. */
    default AccountTaskState requestAccountExport(String accessToken) {
        throw new UnsupportedOperationException("Account export is unavailable");
    }

    /** Returns the exported payload JSON once the task is ready; throws when not ready or owned by someone else. */
    default String getAccountExport(String accessToken, String taskId) {
        throw new UnsupportedOperationException("Account export is unavailable");
    }

    /** Starts account deletion with a cancel window; the account stays usable until the window closes. */
    default AccountTaskState requestAccountDeletion(String accessToken) {
        throw new UnsupportedOperationException("Account deletion is unavailable");
    }

    default AccountTaskState cancelAccountDeletion(String accessToken) {
        throw new UnsupportedOperationException("Account deletion is unavailable");
    }

    default AccountTaskState getAccountDeletionStatus(String accessToken) {
        throw new UnsupportedOperationException("Account deletion is unavailable");
    }
}
