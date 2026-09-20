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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class V111AccountStoreTest {
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    @TempDir Path directory;

    private V111AccountStore newStore() throws Exception {
        return new V111AccountStore(directory.resolve("cloud.db"), new FileMailSender(directory));
    }

    private String insertUser(java.sql.Connection connection, String id, String email) throws Exception {
        return insertUser(connection, id, email, 1);
    }

    private String insertUser(java.sql.Connection connection, String id, String email, int verified) throws Exception {
        try (var statement = connection.prepareStatement(
            "insert into users(id,email,display_name,password_hash,password_salt,disabled,created_at,email_verified) values(?,?,?,?,?,0,?,?)")) {
            statement.setString(1, id); statement.setString(2, email); statement.setString(3, "Student");
            statement.setBytes(4, new byte[]{1}); statement.setBytes(5, new byte[]{2}); statement.setString(6, Instant.now().toString());
            statement.setInt(7, verified);
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

    /** Reads every mailed 6-digit code in file-creation order (oldest first). */
    private List<String> readCodesFromOutbox() throws Exception {
        Path mails = directory.resolve("mails");
        if (!Files.isDirectory(mails)) return List.of();
        try (var entries = Files.list(mails)) {
            List<String> codes = entries.filter(p -> p.toString().endsWith(".mail"))
                .sorted(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                .map(p -> {
                    try {
                        Matcher matcher = CODE.matcher(Files.readString(p));
                        return matcher.find() ? matcher.group(1) : null;
                    } catch (Exception error) { throw new IllegalStateException(error); }
                })
                .toList();
            return codes.stream().filter(java.util.Objects::nonNull).toList();
        }
    }

    private String newestCodeExcluding(List<String> knownCodes) throws Exception {
        List<String> codes = readCodesFromOutbox();
        for (int index = codes.size() - 1; index >= 0; index--) {
            if (!knownCodes.contains(codes.get(index))) return codes.get(index);
        }
        throw new AssertionError("no unused code found in the outbox");
    }

    /** Returns the code with its first digit changed, so it is guaranteed to be wrong. */
    private static String corrupted(String code) {
        char first = code.charAt(0) == '9' ? '0' : (char) (code.charAt(0) + 1);
        return first + code.substring(1);
    }

    @Test void resetCodeFlowChangesPasswordAndRevokesAllSessions() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-reset";
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath())) {
            // email_verified=0：注册后未验证邮箱的账号同样能自助找回（ACC-S2 行为锁定）。
            insertUser(connection, userId, "reset@example.com", 0);
            insertSession(connection, userId, "raw-token-a", false);
            insertSession(connection, userId, "raw-token-b", false);
        }
        store.requestPasswordReset("reset@example.com");
        String code = readCodesFromOutbox().stream().findFirst().orElseThrow(() -> new AssertionError("no mail written"));
        assertFalse(code.isBlank());

        byte[] hashBefore;
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select password_hash from users where id='" + userId + "'")) {
            assertTrue(rows.next());
            hashBefore = rows.getBytes(1);
        }
        store.resetPassword("reset@example.com", code, "brand-new-passphrase-123".toCharArray());

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select password_hash from users where id='" + userId + "'")) {
            assertTrue(rows.next());
            // 对比完整哈希；旧断言只看首字节 ≠1，PBKDF2 随机哈希首字节恰为 1 时会偶发误报。
            assertFalse(java.util.Arrays.equals(hashBefore, rows.getBytes(1)),
                "password hash must change after reset");
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from access_tokens where user_id='" + userId + "' and revoked_at is null")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "all sessions must be revoked after reset");
        }
    }

    @Test void legacyTokenResetStillWorksAdditively() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-legacy";
        byte[] hashBefore;
        try (var connection = connection()) {
            insertUser(connection, userId, "legacy@example.com");
            try (var rows = connection.createStatement().executeQuery("select password_hash from users where id='" + userId + "'")) {
                assertTrue(rows.next());
                hashBefore = rows.getBytes(1);
            }
        }
        String legacyToken = "legacy-raw-reset-token";
        try (var connection = connection(); var statement = connection.prepareStatement(
            "insert into reset_tokens(id,user_id,token_hash,created_at,expires_at,used_at,attempts) values(?,?,?,?,?,null,0)")) {
            statement.setString(1, java.util.UUID.randomUUID().toString()); statement.setString(2, userId);
            statement.setBytes(3, MessageDigest.getInstance("SHA-256").digest(legacyToken.getBytes(StandardCharsets.UTF_8)));
            statement.setString(4, Instant.now().toString());
            statement.setString(5, Instant.now().plus(15, ChronoUnit.MINUTES).toString());
            statement.executeUpdate();
        }
        // The shipped token parameter keeps working alongside the code path (additive contract).
        store.resetPassword(legacyToken, "brand-new-passphrase-123".toCharArray());
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select password_hash from users where id='" + userId + "'")) {
            assertTrue(rows.next());
            assertFalse(java.util.Arrays.equals(hashBefore, rows.getBytes(1)),
                "the legacy token path must still reset the password");
        }
    }

    @Test void reusedResetCodesAreRejected() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-expiry";
        try (var connection = connection()) {
            insertUser(connection, userId, "expiry@example.com");
        }
        store.requestPasswordReset("expiry@example.com");
        String code = readCodesFromOutbox().stream().findFirst().orElseThrow();
        store.resetPassword("expiry@example.com", code, "first-new-passphrase-123".toCharArray());
        IllegalArgumentException replay = assertThrows(IllegalArgumentException.class,
            () -> store.resetPassword("expiry@example.com", code, "second-new-passphrase-456".toCharArray()));
        assertEquals("reset code is invalid or has expired", replay.getMessage());
    }

    @Test void wrongCodeAndUnknownEmailAreIndistinguishable() throws Exception {
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-uniform", "uniform@example.com");
        }
        store.requestPasswordReset("uniform@example.com");
        String code = readCodesFromOutbox().stream().findFirst().orElseThrow();
        IllegalArgumentException wrongCode = assertThrows(IllegalArgumentException.class,
            () -> store.resetPassword("uniform@example.com", corrupted(code), "brand-new-passphrase-123".toCharArray()));
        IllegalArgumentException unknownEmail = assertThrows(IllegalArgumentException.class,
            () -> store.resetPassword("nobody@example.com", "123456", "brand-new-passphrase-123".toCharArray()));
        assertEquals(wrongCode.getMessage(), unknownEmail.getMessage(),
            "the code path must not reveal whether the account exists");
    }

    @Test void doesNotRevealWhetherEmailExists() throws Exception {
        V111AccountStore store = newStore();
        assertDoesNotThrow(() -> store.requestPasswordReset("nobody@example.com"));
        assertTrue(readCodesFromOutbox().isEmpty(), "no mail must be sent for unknown emails");
    }

    @Test void aNewResetCodeInvalidatesThePreviousOne() throws Exception {
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-rotate", "rotate@example.com");
        }
        store.requestPasswordReset("rotate@example.com");
        String firstCode = readCodesFromOutbox().stream().findFirst().orElseThrow();
        store.requestPasswordReset("rotate@example.com");
        String secondCode = newestCodeExcluding(List.of(firstCode));

        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
            () -> store.resetPassword("rotate@example.com", firstCode, "brand-new-passphrase-123".toCharArray()),
            "the older code must be invalidated by the newer request");
        assertEquals("reset code is invalid or has expired", stale.getMessage());
        assertDoesNotThrow(() -> store.resetPassword("rotate@example.com", secondCode, "brand-new-passphrase-123".toCharArray()));
    }

    @Test void resetCodeIsInvalidatedAfterFiveFailedAttempts() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-attempts";
        try (var connection = connection()) {
            insertUser(connection, userId, "attempts@example.com");
        }
        store.requestPasswordReset("attempts@example.com");
        String code = readCodesFromOutbox().stream().findFirst().orElseThrow();

        for (int index = 0; index < 5; index++) {
            final int attempt = index + 1;
            assertThrows(IllegalArgumentException.class,
                () -> store.resetPassword("attempts@example.com", code, ("short-" + attempt).toCharArray()),
                "failed attempt " + attempt + " must be rejected");
        }
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
            () -> store.resetPassword("attempts@example.com", code, "brand-new-passphrase-123".toCharArray()),
            "the attempts threshold must reject the code after five failures");
        assertEquals("reset code is invalid or has expired", blocked.getMessage());
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select password_hash from users where id='" + userId + "'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getBytes(1)[0], "failed attempts must not change the password");
        }

        // A freshly requested code is unaffected: the burn is per code, not per account.
        store.requestPasswordReset("attempts@example.com");
        String freshCode = newestCodeExcluding(List.of(code));
        store.resetPassword("attempts@example.com", freshCode, "brand-new-passphrase-123".toCharArray());
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select password_hash from users where id='" + userId + "'")) {
            assertTrue(rows.next());
            assertNotEquals(1, rows.getBytes(1)[0], "the fresh code must still reset the password");
        }
    }

    @Test void revokeProtectsCurrentSessionAndRevokesOthers() throws Exception {
        V111AccountStore store = newStore();
        String userId = "user-sessions";
        try (var connection = connection()) {
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
        try (var connection = connection()) {
            insertUser(connection, userId, "delete@example.com");
        }
        AccountTaskState pending = store.requestAccountDeletion(userId);
        assertEquals(AccountTaskState.Status.PENDING, pending.status());
        assertNotNull(pending.cancelBefore());
        AccountTaskState cancelled = store.cancelAccountDeletion(userId);
        assertEquals(AccountTaskState.Status.CANCELLED, cancelled.status());
        assertEquals(AccountTaskState.Status.CANCELLED, store.getAccountDeletionStatus(userId).status());
    }

    @Test void confirmEmailVerificationIsSingleUseAndBindsTheEmail() throws Exception {
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-bind", "old@example.com");
        }
        store.requestEmailVerification("user-bind", "new@example.com");
        String code = readCodesFromOutbox().stream().findFirst().orElseThrow();

        store.confirmEmailVerification("user-bind", code);

        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select email,email_verified from users where id='user-bind'")) {
            assertTrue(rows.next());
            assertEquals("new@example.com", rows.getString("email"), "the verified address must replace the old one");
            assertEquals(1, rows.getInt("email_verified"));
        }
        IllegalArgumentException replay = assertThrows(IllegalArgumentException.class,
            () -> store.confirmEmailVerification("user-bind", code), "a consumed code must not confirm twice");
        assertEquals("verification code is invalid or has expired", replay.getMessage());
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select email from users where id='user-bind'")) {
            assertTrue(rows.next());
            assertEquals("new@example.com", rows.getString("email"), "a replay must not change the bound email");
        }
    }

    @Test void bindingAnEmailOwnedByAnotherAccountIsRejected() throws Exception {
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-bind-a", "owner-a@example.com", 0);
            insertUser(connection, "user-bind-b", "owner-b@example.com");
        }
        store.requestEmailVerification("user-bind-a", "owner-b@example.com");
        String code = readCodesFromOutbox().stream().findFirst().orElseThrow();

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
            () -> store.confirmEmailVerification("user-bind-a", code),
            "a verification code must not steal another account's address");
        assertEquals("email is already registered", rejected.getMessage());
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select email,email_verified from users where id='user-bind-a'")) {
            assertTrue(rows.next());
            assertEquals("owner-a@example.com", rows.getString("email"), "the failed bind must leave the account untouched");
            assertEquals(0, rows.getInt("email_verified"));
        }
    }

    @Test void verificationMailsAreRateLimitedPerAccountAndPerTargetEmail() throws Exception {
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-mail-a", "mail-a@example.com");
            insertUser(connection, "user-mail-b", "mail-b@example.com");
        }
        // Per-account hourly cap: three distinct targets succeed, a fourth is refused.
        store.requestEmailVerification("user-mail-a", "a1@example.com");
        store.requestEmailVerification("user-mail-a", "a2@example.com");
        store.requestEmailVerification("user-mail-a", "a3@example.com");
        assertThrows(AuthRateLimiter.RateLimitedException.class,
            () -> store.requestEmailVerification("user-mail-a", "a4@example.com"),
            "the per-account hourly cap must engage after three mails");
        // Per-target-email daily cap: a different account cannot mail an address that already received one.
        assertThrows(AuthRateLimiter.RateLimitedException.class,
            () -> store.requestEmailVerification("user-mail-b", "a1@example.com"),
            "the per-target-email daily cap must hold across accounts");
        assertDoesNotThrow(() -> store.requestEmailVerification("user-mail-b", "b1@example.com"),
            "a fresh account and target email stay allowed");
    }

    @Test void profileUpdateChangesDisplayNameAndRejectsInvalidValues() throws Exception {
        V111AccountStore store = newStore();
        try (var connection = connection()) {
            insertUser(connection, "user-profile", "profile@example.com");
        }
        store.updateProfile("user-profile", "  新名字  ");
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery("select display_name from users where id='user-profile'")) {
            assertTrue(rows.next());
            assertEquals("新名字", rows.getString("display_name"), "the display name must be trimmed and updated");
        }
        assertThrows(IllegalArgumentException.class, () -> store.updateProfile("user-profile", "  "));
        assertThrows(IllegalArgumentException.class, () -> store.updateProfile("user-profile", "x".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> store.updateProfile("missing-user", "whatever"));
        try (var connection = connection();
             var rows = connection.createStatement().executeQuery(
                 "select count(*) from admin_audit where actor_user_id='user-profile' and action='AUTH_PROFILE_UPDATED'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "profile changes must be audited");
        }
    }

    private java.sql.Connection connection() throws Exception {
        return java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
    }
    private static String hexOf(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
