package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AccountTaskState;
import com.sqlteacher.application.support.ProblemReportReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class V111AccountStoreTest {
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");
    @TempDir Path directory;

    private V111AccountStore newStore() throws Exception {
        return new V111AccountStore(directory.resolve("cloud.db"), new FileMailSender(directory));
    }

    private String insertUser(java.sql.Connection connection, String id, String email) throws Exception {
        try (var statement = connection.prepareStatement(
            "insert into users(id,email,display_name,password_hash,password_salt,disabled,created_at,email_verified) values(?,?,?,?,?,0,?,1)")) {
            statement.setString(1, id); statement.setString(2, email); statement.setString(3, "Student");
            statement.setBytes(4, new byte[]{1}); statement.setBytes(5, new byte[]{2}); statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        }
        return id;
    }

    private String insertSession(java.sql.Connection connection, String userId, String rawToken, boolean revoked) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        try (var statement = connection.prepareStatement(
            "insert into access_tokens(token_hash,user_id,expires_at,created_at,revoked_at,device_label,last_seen_at) values(?,?,?,?,?,?,?)")) {
            statement.setBytes(1, MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
            statement.setString(2, userId); statement.setString(3, Instant.now().plus(8, ChronoUnit.HOURS).toString());
            statement.setString(4, Instant.now().toString());
            statement.setString(5, revoked ? Instant.now().toString() : null);
            statement.setString(6, "桌面设备"); statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        }
        return hash;
    }

    private String readTokenFromOutbox() throws Exception {
        try (var entries = Files.list(directory.resolve("mails"))) {
            Path mail = entries.filter(p -> p.toString().endsWith(".mail")).findFirst().orElseThrow(() -> new AssertionError("no mail written"));
            String content = Files.readString(mail);
            Matcher matcher = TOKEN.matcher(content);
            assertTrue(matcher.find(), "mail must contain a token");
            return matcher.group(1);
        }
    }

    @Test void resetFlowChangesPasswordAndRevokesAllSessions() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-reset";
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath())) {
            insertUser(connection, userId, "reset@example.com");
            insertSession(connection, userId, "raw-token-a", false);
            insertSession(connection, userId, "raw-token-b", false);
        }
        store.requestPasswordReset("reset@example.com");
        String token = readTokenFromOutbox();
        assertFalse(token.isBlank());
        store.resetPassword(token, "brand-new-passphrase-123".toCharArray());

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select password_hash from users where id='" + userId + "'")) {
            assertTrue(rows.next());
            assertNotEquals(1, rows.getBytes(1)[0], "password hash must change after reset");
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from access_tokens where user_id='" + userId + "' and revoked_at is null")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "all sessions must be revoked after reset");
        }
    }

    @Test void expiredOrReusedResetTokensAreRejected() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-expiry";
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath())) {
            insertUser(connection, userId, "expiry@example.com");
        }
        store.requestPasswordReset("expiry@example.com");
        String token = readTokenFromOutbox();
        store.resetPassword(token, "first-new-passphrase-123".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> store.resetPassword(token, "second-new-passphrase-456".toCharArray()));
    }

    @Test void doesNotRevealWhetherEmailExists() throws Exception {
        V111AccountStore store = newStore();
        assertDoesNotThrow(() -> store.requestPasswordReset("nobody@example.com"));
        Path mails = directory.resolve("mails");
        if (Files.isDirectory(mails)) {
            try (var entries = Files.list(mails)) {
                assertEquals(0, entries.count(), "no mail must be sent for unknown emails");
            }
        }
    }

    @Test void revokeProtectsCurrentSessionAndRevokesOthers() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-sessions";
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath())) {
            insertUser(connection, userId, "sessions@example.com");
        }
        String currentRaw = "current-raw-token";
        try (var connection = connection()) {
            String otherHash = insertSession(connection, userId, "other-raw-token", false);
            insertSession(connection, userId, currentRaw, false);
            var sessions = store.listSessions(userId);
            assertEquals(2, sessions.size());
            assertThrows(IllegalArgumentException.class, () -> store.revokeSession(userId, hexOf("current-raw-token"), currentRaw));
            store.revokeSession(userId, otherHash, currentRaw);
        }
        assertEquals(1, store.listSessions(userId).size());
    }

    @Test void exportContainsOnlyOwnReports() throws Exception {
        V110SupportStore support = new V110SupportStore(directory.resolve("cloud.db"));
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-export", "export@example.com");
            insertUser(connection, "someone-else", "other@example.com");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", "export-1"); body.put("installId", "i1"); body.put("type", "BUG");
        body.put("severity", "MINOR"); body.put("summary", "Mine"); body.put("description", "d");
        body.put("application", Map.of()); body.put("diagnostics", Map.of());
        support.submit(body, "user-export", "127.0.0.1");
        Map<String, Object> other = new LinkedHashMap<>(body);
        other.put("idempotencyKey", "export-2"); other.put("summary", "Theirs");
        support.submit(other, "someone-else", "127.0.0.1");

        AccountTaskState task = store.requestAccountExport("user-export");
        String payload = store.getAccountExport("user-export", task.taskId());
        assertTrue(payload.contains("Mine"));
        assertFalse(payload.contains("Theirs"), "export must not contain other users' reports");
    }

    @Test void deletionHasCancelWindowAndCanBeCancelled() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-delete";
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath())) {
            insertUser(connection, userId, "delete@example.com");
        }
        AccountTaskState pending = store.requestAccountDeletion(userId);
        assertEquals(AccountTaskState.Status.PENDING, pending.status());
        assertNotNull(pending.cancelBefore());
        AccountTaskState cancelled = store.cancelAccountDeletion(userId);
        assertEquals(AccountTaskState.Status.CANCELLED, cancelled.status());
        assertEquals(AccountTaskState.Status.CANCELLED, store.getAccountDeletionStatus(userId).status());
    }

    private java.sql.Connection connection() throws Exception {
        return java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
    }
    private static String hexOf(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
