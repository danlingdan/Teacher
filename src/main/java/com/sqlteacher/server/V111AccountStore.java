package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AccountTaskState;
import com.sqlteacher.application.collaboration.ActiveSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Account lifecycle store for v1.11: verified-email password reset, active-session
 * management, cloud data export and account deletion with a cancel window.
 *
 * <p>Password hashing mirrors {@code CloudStore} (PBKDF2-HMAC-SHA256, 310k
 * iterations) and token hashes are SHA-256 so no plaintext token is ever stored.</p>
 */
final class V111AccountStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final int TOKEN_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int HASH_BITS = 256;
    private static final int RESET_TOKEN_MINUTES = 30;
    private static final int RESET_MAX_ATTEMPTS = 5;
    private static final int DELETE_CANCEL_DAYS = 7;
    private final String url;
    private final SecureRandom random = new SecureRandom();
    private final MailSender mail;

    V111AccountStore(java.nio.file.Path database, MailSender mail) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        this.mail = mail;
        initialize();
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
        if (MessageDigest.isEqual(target, tokenHash(currentRawToken))) {
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
        String token = randomToken(); Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into email_verifications(id,user_id,email,token_hash,created_at,expires_at,used_at) values(?,?,?,?,?,?,null)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, userId);
            statement.setString(3, email); statement.setBytes(4, tokenHash(token));
            statement.setString(5, now.toString()); statement.setString(6, now.plus(30, ChronoUnit.MINUTES).toString());
            statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
        mail.send(email, "SQLTeacher 邮箱验证", "验证链接（30 分钟内有效，仅限一次）：\nhttps://api.sqlteacher.tech/verify-email?token=" + token);
    }

    void confirmEmailVerification(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("verification token is invalid");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id,user_id,expires_at,used_at from email_verifications where token_hash=?")) {
            statement.setBytes(1, tokenHash(token));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("verification token is invalid");
                String used = row.getString("used_at");
                if (used != null || Instant.parse(row.getString("expires_at")).isBefore(Instant.now())) {
                    throw new IllegalArgumentException("verification token has expired or already been used");
                }
                String now = Instant.now().toString();
                try (PreparedStatement update = connection.prepareStatement("update email_verifications set used_at=? where id=?")) {
                    update.setString(1, now); update.setString(2, row.getString("id")); update.executeUpdate();
                }
                try (PreparedStatement user = connection.prepareStatement("update users set email_verified=1 where id=?")) {
                    user.setString(1, row.getString("user_id")); user.executeUpdate();
                }
            }
        } catch (SQLException error) { throw database(error); }
    }

    // ---- password reset ----

    void requestPasswordReset(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String userId = null;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id,email_verified from users where email=?")) {
            statement.setString(1, normalized);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next() && row.getInt("email_verified") == 1) userId = row.getString("id");
            }
        } catch (SQLException error) { throw database(error); }
        if (userId == null) return; // uniform response; no account enumeration
        String token = randomToken(); Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into reset_tokens(id,user_id,token_hash,created_at,expires_at,used_at,attempts) values(?,?,?,?,?,null,0)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, userId);
            statement.setBytes(3, tokenHash(token)); statement.setString(4, now.toString());
            statement.setString(5, now.plus(RESET_TOKEN_MINUTES, ChronoUnit.MINUTES).toString()); statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
        mail.send(normalized, "SQLTeacher 密码重置", "重置链接（30 分钟内有效，仅限一次）：\nhttps://api.sqlteacher.tech/reset-password?token=" + token);
    }

    void resetPassword(String token, char[] newPassword) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("reset token is invalid");
        validatePassword(newPassword);
        byte[] hash = tokenHash(token);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            String userId;
            try (PreparedStatement statement = connection.prepareStatement(
                "select id,user_id,expires_at,used_at,attempts from reset_tokens where token_hash=?")) {
                statement.setBytes(1, hash);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("reset token is invalid");
                    String used = row.getString("used_at");
                    if (used != null || Instant.parse(row.getString("expires_at")).isBefore(Instant.now())
                        || row.getInt("attempts") >= RESET_MAX_ATTEMPTS) {
                        throw new IllegalArgumentException("reset token has expired or already been used");
                    }
                    userId = row.getString("user_id");
                }
            }
            byte[] salt = bytes(SALT_BYTES); byte[] passwordHash = hash(newPassword, salt);
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
            connection.commit();
        } catch (SQLException error) { throw database(error); }
        finally { java.util.Arrays.fill(newPassword, '\0'); }
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
        String payload;
        try {
            payload = JSON.writeValueAsString(Map.of("exportedAt", Instant.now().toString(), "problemReports", reportRows));
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

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            // Mirror the core CloudStore tables so this store can also be initialized independently (tests);
            // in the running server these are no-ops because the tables already exist.
            statement.executeUpdate("create table if not exists users(id text primary key,email text unique not null,display_name text not null,password_hash blob not null,password_salt blob not null,disabled integer not null default 0,created_at text not null,email_verified integer not null default 0)");
            statement.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text,device_label text,last_seen_at text)");
            statement.executeUpdate("create table if not exists refresh_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
            statement.executeUpdate("create table if not exists email_verifications(id text primary key,user_id text not null references users(id),email text not null,token_hash blob not null,created_at text not null,expires_at text not null,used_at text)");
            statement.executeUpdate("create table if not exists reset_tokens(id text primary key,user_id text not null references users(id),token_hash blob not null,created_at text not null,expires_at text not null,used_at text,attempts integer not null default 0)");
            statement.executeUpdate("create table if not exists account_tasks(id text primary key,user_id text not null references users(id),kind text not null,status text not null,payload_json text,cancel_before text,created_at text not null,updated_at text not null)");
            statement.executeUpdate("create index if not exists idx_reset_tokens_user on reset_tokens(user_id)");
            statement.executeUpdate("create index if not exists idx_account_tasks_user on account_tasks(user_id,kind)");
            addColumnIfMissing(connection, "access_tokens", "device_label", "text");
            addColumnIfMissing(connection, "access_tokens", "last_seen_at", "text");
            addColumnIfMissing(connection, "users", "email_verified", "integer not null default 0");
        }
    }
    private static void addColumnIfMissing(Connection connection, String table, String column, String definition) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            if (columns.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        }
    }
    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys=on"); statement.execute("pragma busy_timeout=5000");
        }
        return connection;
    }
    private byte[] tokenHash(String token) { return sha256(token); }
    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static byte[] hexBytes(String hex) {
        try { return HexFormat.of().parseHex(hex); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("session id is invalid"); }
    }
    private byte[] hash(char[] password, byte[] salt) {
        try { KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BITS); return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception error) { throw new IllegalStateException("password hashing failed", error); }
    }
    private void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 128) throw new IllegalArgumentException("password must be 12 to 128 characters");
    }
    private String randomToken() { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(TOKEN_BYTES)); }
    private byte[] bytes(int size) { byte[] value = new byte[size]; random.nextBytes(value); return value; }
    private static IllegalStateException database(SQLException error) { return new IllegalStateException("Account database operation failed", error); }
}
