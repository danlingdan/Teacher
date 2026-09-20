package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AccountTaskState;
import com.sqlteacher.application.collaboration.ActiveSession;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Account lifecycle store for v1.11: verified-email password reset, active-session
 * management, cloud data export and account deletion with a cancel window.
 *
 * <p>Password hashing mirrors {@code CloudStore} (PBKDF2-HMAC-SHA256, 310k
 * iterations) and token hashes are SHA-256 so no plaintext token is ever stored.</p>
 *
 * <p>v3.8.0 ACC-S2: reset and email-verification credentials are 6-digit mailed
 * codes hashed with the same SHA-256 column (15-minute reset window). Codes are
 * bound to the account at query level, burned after {@value #RESET_MAX_ATTEMPTS}
 * failed confirmations, and every mailed-code failure mode collapses into one
 * uniform message so responses never reveal whether an account exists.</p>
 */
final class V111AccountStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = CloudJsonStoreSupport.mapper();
    private static final int SALT_BYTES = 16;
    private static final int CODE_DIGITS = 6;
    private static final int RESET_CODE_MINUTES = 15;
    private static final int VERIFICATION_CODE_MINUTES = 30;
    private static final int RESET_MAX_ATTEMPTS = 5;
    private static final int DELETE_CANCEL_DAYS = 7;
    private static final int VERIFICATION_MAILS_PER_ACCOUNT_PER_HOUR = 3;
    private static final int VERIFICATION_MAILS_PER_EMAIL_PER_DAY = 1;
    private static final Duration VERIFICATION_ACCOUNT_WINDOW = Duration.ofHours(1);
    private static final Duration VERIFICATION_EMAIL_WINDOW = Duration.ofDays(1);
    // Single uniform failure message for the code path: unknown email, wrong code,
    // expired code, reused code and exhausted attempts are indistinguishable.
    private static final String RESET_CODE_INVALID = "reset code is invalid or has expired";
    private static final String VERIFICATION_CODE_INVALID = "verification code is invalid or has expired";
    private final String url;
    private final MailSender mail;
    private final AuthRateLimiter verificationMailLimiter = new AuthRateLimiter();

    V111AccountStore(java.nio.file.Path database, MailSender mail) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        this.mail = mail;
        // v3.4.0 REF-5: schema evolution is owned by the shared versioned migrator.
        CloudSchemaMigrator.migrate(database.toAbsolutePath().normalize());
    }

    // ---- sessions ----

    List<ActiveSession> listSessions(String userId) {
        List<ActiveSession> sessions = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select token_hash,device_label,created_at,last_seen_at from access_tokens "
                + "where user_id=? and revoked_at is null and expires_at>? order by created_at desc")) {
            statement.setString(1, userId); statement.setString(2, Instant.now().toString());
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    String lastSeen = row.getString("last_seen_at");
                    sessions.add(new ActiveSession(HexFormat.of().formatHex(row.getBytes("token_hash")),
                        row.getString("device_label"), Instant.parse(row.getString("created_at")),
                        lastSeen == null ? Instant.parse(row.getString("created_at")) : Instant.parse(lastSeen)));
                }
            }
        } catch (SQLException error) { throw database(error); }
        return List.copyOf(sessions);
    }

    /** Revokes another session; the caller's own session (matched by raw token hash) is protected. */
    void revokeSession(String userId, String sessionIdHex, String currentRawToken) {
        byte[] target = hexBytes(sessionIdHex);
        if (Hashes.constantTimeEquals(target, tokenHash(currentRawToken))) {
            throw new IllegalArgumentException("cannot revoke the current session");
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update access_tokens set revoked_at=? where user_id=? and token_hash=? and revoked_at is null")) {
            statement.setString(1, Instant.now().toString()); statement.setString(2, userId); statement.setBytes(3, target);
            statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
    }

    // ---- email verification ----

    void requestEmailVerification(String userId, String email) {
        // Fail-closed quotas for mail-triggering endpoints: consumed before the mail is written so
        // concurrent retries cannot exceed the caps. Keys use the account id and the normalized
        // target email; neither is logged.
        String accountKey = "verify-mail:account:" + userId;
        String emailKey = "verify-mail:email:" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
        verificationMailLimiter.checkQuota(accountKey, VERIFICATION_MAILS_PER_ACCOUNT_PER_HOUR);
        verificationMailLimiter.recordEvent(accountKey, VERIFICATION_ACCOUNT_WINDOW);
        verificationMailLimiter.checkQuota(emailKey, VERIFICATION_MAILS_PER_EMAIL_PER_DAY);
        verificationMailLimiter.recordEvent(emailKey, VERIFICATION_EMAIL_WINDOW);
        String code = Hashes.randomNumericCode(CODE_DIGITS);
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into email_verifications(id,user_id,email,token_hash,created_at,expires_at,used_at) values(?,?,?,?,?,?,null)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, userId);
            statement.setString(3, email); statement.setBytes(4, tokenHash(code));
            statement.setString(5, now.toString());
            statement.setString(6, now.plus(VERIFICATION_CODE_MINUTES, ChronoUnit.MINUTES).toString());
            statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
        mail.send(email, "SQLTeacher 邮箱验证",
            "您的邮箱验证码：" + code + "\n\n验证码 " + VERIFICATION_CODE_MINUTES + " 分钟内有效，仅限一次。如非本人操作，请忽略本邮件。");
    }

    void confirmEmailVerification(String userId, String code) {
        if (userId == null || userId.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("verification code is invalid");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String verificationId;
                String email;
                try (PreparedStatement statement = connection.prepareStatement(
                    "select id,email from email_verifications where token_hash=? and user_id=? and used_at is null and expires_at>? "
                        + "order by created_at desc")) {
                    statement.setBytes(1, tokenHash(code.strip()));
                    statement.setString(2, userId);
                    statement.setString(3, Instant.now().toString());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(VERIFICATION_CODE_INVALID);
                        verificationId = row.getString("id");
                        email = row.getString("email");
                    }
                }
                // Atomic consume: only the first concurrent confirmation wins the conditional update.
                int consumed;
                try (PreparedStatement consume = connection.prepareStatement(
                    "update email_verifications set used_at=? where id=? and used_at is null")) {
                    consume.setString(1, Instant.now().toString());
                    consume.setString(2, verificationId);
                    consumed = consume.executeUpdate();
                }
                if (consumed != 1) throw new IllegalArgumentException(VERIFICATION_CODE_INVALID);
                try (PreparedStatement user = connection.prepareStatement(
                    "update users set email=?, email_verified=1 where id=?")) {
                    user.setString(1, email); user.setString(2, userId); user.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sqlError && String.valueOf(sqlError.getMessage()).contains("UNIQUE")) {
                    // Binding an address owned by another account must fail with a clear,
                    // non-database message and leave the current binding untouched.
                    throw new IllegalArgumentException("email is already registered");
                }
                throw error;
            }
        } catch (SQLException error) { throw database(error); }
    }

    // ---- password reset ----

    void requestPasswordReset(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String userId = findUserId(normalized);
        if (userId == null) return; // uniform response; no account enumeration
        // v3.8.0 ACC-S2: an existing account receives a code regardless of email_verified —
        // receiving the code at the mailbox is itself the ownership proof, and gating on
        // verification used to leave unverified accounts permanently unable to self-recover.
        String code = Hashes.randomNumericCode(CODE_DIGITS);
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement invalidate = connection.prepareStatement(
                    "update reset_tokens set used_at=? where user_id=? and used_at is null")) {
                    invalidate.setString(1, now.toString()); invalidate.setString(2, userId);
                    invalidate.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into reset_tokens(id,user_id,token_hash,created_at,expires_at,used_at,attempts) values(?,?,?,?,?,null,0)")) {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, userId);
                    statement.setBytes(3, tokenHash(code)); statement.setString(4, now.toString());
                    statement.setString(5, now.plus(RESET_CODE_MINUTES, ChronoUnit.MINUTES).toString());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) { throw database(error); }
        mail.send(normalized, "SQLTeacher 密码重置",
            "您的密码重置验证码：" + code + "\n\n验证码 " + RESET_CODE_MINUTES
                + " 分钟内有效，仅限一次。如非本人操作，请忽略本邮件。");
    }

    /** Legacy token-based reset kept additively for already-shipped request bodies; prefer the code path. */
    void resetPassword(String token, char[] newPassword) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("reset token is invalid");
        consumeResetToken(tokenHash(token), newPassword, null);
    }

    void resetPassword(String email, String code, char[] newPassword) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException(RESET_CODE_INVALID);
        String userId = findUserId(email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
        if (userId == null) throw new IllegalArgumentException(RESET_CODE_INVALID);
        consumeResetToken(tokenHash(code.strip()), newPassword, userId);
    }

    private void consumeResetToken(byte[] hash, char[] newPassword, String requireUserId) {
        boolean codePath = requireUserId != null;
        String invalidMessage = codePath ? RESET_CODE_INVALID : "reset token is invalid";
        String expiredMessage = codePath ? RESET_CODE_INVALID : "reset token has expired or already been used";
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            String userId;
            try (PreparedStatement statement = connection.prepareStatement(
                codePath
                    ? "select id,user_id,expires_at,used_at,attempts from reset_tokens where token_hash=? and user_id=?"
                    : "select id,user_id,expires_at,used_at,attempts from reset_tokens where token_hash=?")) {
                statement.setBytes(1, hash);
                if (codePath) statement.setString(2, requireUserId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException(invalidMessage);
                    String used = row.getString("used_at");
                    if (used != null || Instant.parse(row.getString("expires_at")).isBefore(Instant.now())
                        || row.getInt("attempts") >= RESET_MAX_ATTEMPTS) {
                        throw new IllegalArgumentException(expiredMessage);
                    }
                    userId = row.getString("user_id");
                }
            }
            try {
                validatePassword(newPassword);
                byte[] salt = Hashes.randomBytes(SALT_BYTES); byte[] passwordHash = Hashes.pbkdf2Hash(newPassword, salt);
                try (PreparedStatement update = connection.prepareStatement("update users set password_hash=?,password_salt=? where id=?")) {
                    update.setBytes(1, passwordHash); update.setBytes(2, salt); update.setString(3, userId); update.executeUpdate();
                }
                String now = Instant.now().toString();
                try (PreparedStatement revokeAccess = connection.prepareStatement("update access_tokens set revoked_at=? where user_id=? and revoked_at is null");
                     PreparedStatement revokeRefresh = connection.prepareStatement("update refresh_tokens set revoked_at=? where user_id=? and revoked_at is null")) {
                    revokeAccess.setString(1, now); revokeAccess.setString(2, userId); revokeAccess.executeUpdate();
                    revokeRefresh.setString(1, now); revokeRefresh.setString(2, userId); revokeRefresh.executeUpdate();
                }
                try (PreparedStatement markUsed = connection.prepareStatement("update reset_tokens set used_at=? where token_hash=?")) {
                    markUsed.setString(1, now); markUsed.setBytes(2, hash); markUsed.executeUpdate();
                }
                audit(connection, userId, "AUTH_PASSWORD_RESET", "USER", userId, "SUCCESS", "MAILED_CODE");
                connection.commit();
            } catch (RuntimeException failure) {
                // The credential matched but the attempt failed (for example an invalid new password):
                // roll back and burn one of the RESET_MAX_ATTEMPTS so repeated failed attempts
                // against a live credential invalidate it.
                try {
                    connection.rollback();
                    try (PreparedStatement bump = connection.prepareStatement(
                        "update reset_tokens set attempts=attempts+1 where token_hash=? and used_at is null")) {
                        bump.setBytes(1, hash);
                        bump.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException suppressed) {
                    failure.addSuppressed(suppressed);
                }
                throw failure;
            }
        } catch (SQLException error) { throw database(error); }
        finally { java.util.Arrays.fill(newPassword, '\0'); }
    }

    // ---- profile ----

    /** v3.8.0 ACC-S3: self-service display-name change (email and password have their own flows). */
    void updateProfile(String userId, String displayName) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("account does not exist");
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 80) {
            throw new IllegalArgumentException("displayName must be 1 to 80 characters");
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update users set display_name=? where id=?")) {
            statement.setString(1, displayName.strip()); statement.setString(2, userId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("account does not exist");
            audit(connection, userId, "AUTH_PROFILE_UPDATED", "USER", userId, "SUCCESS", "SELF_SERVICE");
        } catch (SQLException error) { throw database(error); }
    }

    // ---- account export / deletion ----

    AccountTaskState requestAccountExport(String userId) {
        List<Object> reportRows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id,type,status,summary,created_at from problem_reports where user_id=? order by created_at")) {
            statement.setString(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) reportRows.add(Map.of("id", row.getString("id"), "type", row.getString("type"),
                    "status", row.getString("status"), "summary", row.getString("summary"), "submittedAt", row.getString("created_at")));
            }
        } catch (SQLException error) { throw database(error); }
        // v3.7.0 TFB-C3：数据主体可取回本人已同步的学习记录（与教师明细同源，仅限本人）。
        List<Object> learningEvents = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select event_id,event_type,payload_json,occurred_at from sync_events where user_id=? order by occurred_at")) {
            statement.setString(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) learningEvents.add(Map.of("eventId", row.getString("event_id"),
                    "eventType", row.getString("event_type"), "payload", row.getString("payload_json"),
                    "occurredAt", row.getString("occurred_at")));
            }
        } catch (SQLException error) { throw database(error); }
        String payload;
        try {
            java.util.Map<String, Object> export = new java.util.LinkedHashMap<>();
            export.put("exportedAt", Instant.now().toString());
            export.put("problemReports", reportRows);
            export.put("learningEvents", learningEvents);
            payload = JSON.writeValueAsString(export);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Unable to build account export", error);
        }
        return insertTask(userId, "EXPORT", "READY", payload, null);
    }

    String getAccountExport(String userId, String taskId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select status,payload_json from account_tasks where id=? and user_id=? and kind='EXPORT'")) {
            statement.setString(1, taskId); statement.setString(2, userId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("export task does not exist");
                if (!"READY".equals(row.getString("status"))) throw new IllegalArgumentException("export task is not ready");
                return row.getString("payload_json");
            }
        } catch (SQLException error) { throw database(error); }
    }

    AccountTaskState requestAccountDeletion(String userId) {
        Instant now = Instant.now();
        return insertTask(userId, "DELETE", "PENDING", "", now.plus(DELETE_CANCEL_DAYS, ChronoUnit.DAYS));
    }

    AccountTaskState cancelAccountDeletion(String userId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id,cancel_before from account_tasks where user_id=? and kind='DELETE' and status='PENDING' order by created_at desc limit 1")) {
            statement.setString(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("no pending deletion");
                String cancelBefore = row.getString("cancel_before");
                if (Instant.parse(cancelBefore).isBefore(Instant.now())) throw new IllegalArgumentException("deletion window has closed");
                try (PreparedStatement update = connection.prepareStatement("update account_tasks set status='CANCELLED',updated_at=? where id=?")) {
                    update.setString(1, Instant.now().toString()); update.setString(2, row.getString("id")); update.executeUpdate();
                }
                return getAccountDeletionStatus(userId);
            }
        } catch (SQLException error) { throw database(error); }
    }

    AccountTaskState getAccountDeletionStatus(String userId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id,status,created_at,updated_at,cancel_before from account_tasks where user_id=? and kind='DELETE' order by created_at desc limit 1")) {
            statement.setString(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return new AccountTaskState("", "DELETE", AccountTaskState.Status.PENDING, Instant.now(), Instant.now(), null);
                String cancelBefore = row.getString("cancel_before");
                return new AccountTaskState(row.getString("id"), "DELETE", AccountTaskState.Status.valueOf(row.getString("status")),
                    Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")),
                    cancelBefore == null ? null : Instant.parse(cancelBefore));
            }
        } catch (SQLException error) { throw database(error); }
    }

    private AccountTaskState insertTask(String userId, String kind, String status, String payload, Instant cancelBefore) {
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into account_tasks(id,user_id,kind,status,payload_json,cancel_before,created_at,updated_at) values(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id); statement.setString(2, userId); statement.setString(3, kind); statement.setString(4, status);
            statement.setString(5, payload); statement.setString(6, cancelBefore == null ? null : cancelBefore.toString());
            statement.setString(7, now.toString()); statement.setString(8, now.toString()); statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
        return new AccountTaskState(id, kind, AccountTaskState.Status.valueOf(status), now, now, cancelBefore);
    }

    // ---- helpers ----

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys=on"); statement.execute("pragma busy_timeout=5000");
        }
        return connection;
    }
    private String findUserId(String normalizedEmail) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id from users where email=?")) {
            statement.setString(1, normalizedEmail);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException error) { throw database(error); }
    }

    /** Mirrors {@code CloudStoreBase.audit} so account lifecycle events land in the same audit trail. */
    private void audit(Connection connection, String actorUserId, String action, String targetType,
                       String targetId, String result, String reasonCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into admin_audit(id,actor_user_id,action,target_type,target_id,result,reason_code,"
                + "correlation_id,created_at) values(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actorUserId);
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.setString(6, result);
            statement.setString(7, reasonCode);
            statement.setString(8, UUID.randomUUID().toString());
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
        }
    }
    private byte[] tokenHash(String token) { return Hashes.sha256Bytes(token); }
    private static byte[] hexBytes(String hex) {
        try { return HexFormat.of().parseHex(hex); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("session id is invalid"); }
    }
    private void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 128) throw new IllegalArgumentException("password must be 12 to 128 characters");
    }
    private static IllegalStateException database(SQLException error) { return new IllegalStateException("Account database operation failed", error); }
}
