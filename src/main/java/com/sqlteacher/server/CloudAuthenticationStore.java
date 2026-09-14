package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.UserRole;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Cloud account, credential, and token persistence (v3.4.0 REF-2): PBKDF2 password hashing,
 * access/refresh token issuance and revocation, self-service password change, and the
 * environment-driven bootstrap administrator. Behavior is unchanged from the former
 * {@code CloudStore} inner class of {@link SqlTeacherCloudServer}.
 */
final class CloudAuthenticationStore extends CloudStoreBase implements CloudAuthenticationService {
    private static final int SALT_BYTES = 16;
    private static final long ACCESS_TOKEN_HOURS = 8;
    private static final long REFRESH_TOKEN_DAYS = 30;

    CloudAuthenticationStore(Path database) throws SQLException, IOException {
        super(database);
    }

    @Override
    public Session register(String email, String displayName, char[] password) {
        return toSession(registerData(email, displayName, password));
    }

    @Override
    public Session login(String email, char[] password) {
        return toSession(loginData(email, password));
    }

    @Override
    public AuthenticatedUser authenticate(String accessToken) {
        return authenticateData(accessToken);
    }

    @Override
    public void logout(String accessToken) {
        logout(accessToken, null);
    }

    void logout(String accessToken, String refreshToken) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            revoke(c, "access_tokens", accessToken);
            if (refreshToken != null && !refreshToken.isBlank()) revoke(c, "refresh_tokens", refreshToken);
            c.commit();
        } catch (SQLException e) {
            throw database(e);
        }
    }

    void changePassword(String accessToken, char[] currentPassword, char[] newPassword) {
        AuthenticatedUser actor = authenticateData(accessToken);
        validateLoginPassword(currentPassword);
        validatePassword(newPassword);
        try (Connection connection = open(); PreparedStatement read = connection.prepareStatement(
            "select password_hash,password_salt from users where id=?")) {
            read.setString(1, actor.id());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next() || !Hashes.constantTimeEquals(row.getBytes(1), Hashes.pbkdf2Hash(currentPassword, row.getBytes(2)))) {
                    throw new SecurityException("current password is incorrect");
                }
            }
            byte[] salt = Hashes.randomBytes(SALT_BYTES);
            byte[] passwordHash = Hashes.pbkdf2Hash(newPassword, salt);
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("update users set password_hash=?,password_salt=? where id=?")) {
                update.setBytes(1, passwordHash);
                update.setBytes(2, salt);
                update.setString(3, actor.id());
                update.executeUpdate();
            }
            try (PreparedStatement revokeAccess = connection.prepareStatement("update access_tokens set revoked_at=? where user_id=? and revoked_at is null" );
                 PreparedStatement revokeRefresh = connection.prepareStatement("update refresh_tokens set revoked_at=? where user_id=? and revoked_at is null")) {
                String now = Instant.now().toString();
                revokeAccess.setString(1, now);
                revokeAccess.setString(2, actor.id());
                revokeAccess.executeUpdate();
                revokeRefresh.setString(1, now);
                revokeRefresh.setString(2, actor.id());
                revokeRefresh.executeUpdate();
            }
            audit(connection, actor.id(), "AUTH_PASSWORD_CHANGED", "USER", actor.id(), "SUCCESS", "SELF_SERVICE");
            connection.commit();
        } catch (SQLException error) { throw database(error); }
        finally {
            java.util.Arrays.fill(currentPassword, '\0');
            java.util.Arrays.fill(newPassword, '\0');
        }
    }

    SessionData registerData(String email, String displayName, char[] password) {
        String normalizedEmail = validateEmail(email);
        if (displayName == null || displayName.isBlank() || displayName.length() > 80) throw new IllegalArgumentException("displayName must be 1 to 80 characters");
        validatePassword(password);
        String id = UUID.randomUUID().toString();
        byte[] salt = Hashes.randomBytes(SALT_BYTES);
        byte[] hash = Hashes.pbkdf2Hash(password, salt);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into users(id,email,display_name,password_hash,password_salt,disabled,created_at) values(?,?,?,?,?,0,?)")) {
            statement.setString(1, id);
            statement.setString(2, normalizedEmail);
            statement.setString(3, displayName.trim());
            statement.setBytes(4, hash);
            statement.setBytes(5, salt);
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
            try (PreparedStatement role = connection.prepareStatement("insert into user_roles(user_id,role) values(?, 'STUDENT')")) {
                role.setString(1, id);
                role.executeUpdate();
            }
            audit(connection, id, "AUTH_REGISTER", "USER", id, "SUCCESS", "SELF_SERVICE");
        } catch (SQLException error) {
            if (error.getMessage().contains("UNIQUE")) throw new SecurityException("duplicate account");
            throw database(error);
        }
        return issue(user(id));
    }

    void ensureBootstrapAdmin(String email, char[] password) {
        String normalizedEmail = validateEmail(email);
        try (Connection connection = open();
             PreparedStatement find = connection.prepareStatement("select id from users where email=?")) {
            find.setString(1, normalizedEmail);
            try (ResultSet existing = find.executeQuery()) {
                String userId;
                if (existing.next()) {
                    userId = existing.getString(1);
                } else {
                    userId = registerData(normalizedEmail, "System Administrator", password).user().id();
                }
                try (PreparedStatement role = connection.prepareStatement(
                    "insert or ignore into user_roles(user_id,role) values(?, 'ADMIN')")) {
                    role.setString(1, userId);
                    role.executeUpdate();
                }
            }
        } catch (SQLException error) {
            throw database(error);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    SessionData loginData(String email, char[] password) {
        String normalizedEmail = validateEmail(email);
        validateLoginPassword(password);
        String userId;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id,password_hash,password_salt,disabled from users where email=?")) {
            statement.setString(1, normalizedEmail);
            try (ResultSet result = statement.executeQuery()) {
                boolean exists = result.next();
                userId = exists ? result.getString("id") : null;
                boolean hashable = exists && result.getInt("disabled") == 0;
                boolean valid = hashable
                    && Hashes.constantTimeEquals(result.getBytes("password_hash"),
                        Hashes.pbkdf2Hash(password, result.getBytes("password_salt")));
                if (!valid && !hashable) {
                    // Match the PBKDF2 cost of an existing account so unknown or disabled
                    // accounts fail with the same response time and the identical 401 body.
                    Hashes.pbkdf2Hash(password, Hashes.randomBytes(SALT_BYTES));
                }
                if (!valid) {
                    // Failed logins are not audited; the in-process rate limiter is the
                    // failure record and writing one row per guessing attempt floods admin_audit.
                    throw new SecurityException("invalid credentials");
                }
                audit(connection, userId, "AUTH_LOGIN", "USER", userId, "SUCCESS", "CREDENTIAL_VERIFIED");
            }
        } catch (SQLException error) { throw database(error); }
        return issue(user(userId));
    }

    SessionData refreshData(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("refreshToken must not be blank");
        try (Connection connection = open();
             PreparedStatement token = connection.prepareStatement(
                 "select user_id from refresh_tokens where token_hash=? and expires_at>? and revoked_at is null")) {
            connection.setAutoCommit(false);
            token.setBytes(1, tokenHash(refreshToken));
            token.setString(2, Instant.now().toString());
            String userId;
            try (ResultSet result = token.executeQuery()) {
                if (!result.next()) throw new SecurityException("invalid refresh token");
                userId = result.getString(1);
            }
            try (PreparedStatement revoke = connection.prepareStatement(
                "update refresh_tokens set revoked_at=? where token_hash=? and revoked_at is null")) {
                revoke.setString(1, Instant.now().toString());
                revoke.setBytes(2, tokenHash(refreshToken));
                if (revoke.executeUpdate() != 1) throw new SecurityException("refresh token already used");
            }
            SessionData session = issue(connection, user(userId));
            connection.commit();
            return session;
        } catch (SQLException error) { throw database(error); }
    }

    private AuthenticatedUser authenticateData(String token) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                 "select user_id from access_tokens where token_hash=? and expires_at>? and revoked_at is null")) {
            statement.setBytes(1, tokenHash(token));
            statement.setString(2, Instant.now().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecurityException("invalid token");
                return user(result.getString(1));
            }
        } catch (SQLException error) { throw database(error); }
    }

    private void revoke(Connection c, String table, String token) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("update " + table + " set revoked_at=? where token_hash=? and revoked_at is null")) {
            s.setString(1, Instant.now().toString());
            s.setBytes(2, tokenHash(token));
            s.executeUpdate();
        }
    }

    private SessionData issue(AuthenticatedUser user) {
        try (Connection c = open()) {
            return issue(c, user);
        } catch (SQLException e) {
            throw database(e);
        }
    }

    private SessionData issue(Connection c, AuthenticatedUser user) throws SQLException {
        String token = Hashes.randomToken();
        String refresh = Hashes.randomToken();
        Instant now = Instant.now();
        Instant expiry = now.plus(ACCESS_TOKEN_HOURS, ChronoUnit.HOURS);
        Instant refreshExpiry = now.plus(REFRESH_TOKEN_DAYS, ChronoUnit.DAYS);
        try (PreparedStatement access = c.prepareStatement(
                 "insert into access_tokens(token_hash,user_id,expires_at,created_at,device_label,last_seen_at) values(?,?,?,?,?,?)");
             PreparedStatement refreshStatement = c.prepareStatement(
                 "insert into refresh_tokens(token_hash,user_id,expires_at,created_at) values(?,?,?,?)")) {
            access.setBytes(1, tokenHash(token));
            access.setString(2, user.id());
            access.setString(3, expiry.toString());
            access.setString(4, now.toString());
            access.setString(5, "桌面设备");
            access.setString(6, now.toString());
            access.executeUpdate();
            refreshStatement.setBytes(1, tokenHash(refresh));
            refreshStatement.setString(2, user.id());
            refreshStatement.setString(3, refreshExpiry.toString());
            refreshStatement.setString(4, now.toString());
            refreshStatement.executeUpdate();
        }
        return new SessionData(token, expiry, user, refresh);
    }

    private AuthenticatedUser user(String id) {
        try (Connection c = open();
             PreparedStatement s = c.prepareStatement("select id,email,display_name from users where id=? and disabled=0")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SecurityException("unknown user");
                Set<UserRole> roles = new java.util.HashSet<>();
                try (PreparedStatement rs = c.prepareStatement("select role from user_roles where user_id=?")) {
                    rs.setString(1, id);
                    try (ResultSet rr = rs.executeQuery()) {
                        while (rr.next()) roles.add(UserRole.valueOf(rr.getString(1)));
                    }
                }
                return new AuthenticatedUser(r.getString(1), r.getString(2), r.getString(3), roles);
            }
        } catch (SQLException e) {
            throw database(e);
        }
    }

    String userIdByEmail(String email) {
        String normalized = validateEmail(email);
        try (Connection c = open();
             PreparedStatement s = c.prepareStatement("select id from users where email=? and disabled=0")) {
            s.setString(1, normalized);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("User email was not found");
                return r.getString(1);
            }
        } catch (SQLException e) {
            throw database(e);
        }
    }

    private static String validateEmail(String e) {
        if (e == null || !e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || e.length() > 254) throw new IllegalArgumentException("email must be valid");
        return e.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateLoginPassword(char[] p) {
        if (p == null || p.length == 0 || p.length > 128) throw new IllegalArgumentException("password must contain 1 to 128 characters");
    }

    private static void validatePassword(char[] p) {
        if (p == null || p.length < 12 || p.length > 128) throw new IllegalArgumentException("password must contain 12 to 128 characters");
    }

    private static CloudAuthenticationService.Session toSession(SessionData s) {
        return new CloudAuthenticationService.Session(s.token(), s.expiresAt(), s.user(), s.refreshToken());
    }
}
