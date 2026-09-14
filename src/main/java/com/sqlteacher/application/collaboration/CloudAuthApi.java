package com.sqlteacher.application.collaboration;

/** Desktop boundary for cloud sign-in, registration, sessions refresh, and passwords (v3.4.0 REF-4 port split). */
public interface CloudAuthApi {
    CloudAuthenticationService.Session login(String email, char[] password);

    CloudAuthenticationService.Session register(String email, String displayName, char[] password);

    CloudAuthenticationService.Session refresh(String refreshToken);

    void logout(String accessToken);

    default void logout(String accessToken, String refreshToken) {
        logout(accessToken);
    }

    default void changePassword(String accessToken, char[] currentPassword, char[] newPassword) {
        throw new UnsupportedOperationException("Password changes are unavailable");
    }

    /** Requests a password-reset email for an account. Always returns normally to avoid account enumeration. */
    default void requestPasswordReset(String email) {
        throw new UnsupportedOperationException("Password reset is unavailable");
    }

    /** Resets the account password using a one-time token from the reset email; revokes all sessions on success. */
    default void resetPassword(String token, char[] newPassword) {
        throw new UnsupportedOperationException("Password reset is unavailable");
    }
}
