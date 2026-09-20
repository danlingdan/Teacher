package com.sqlteacher.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.AdminAuditEntry;
import com.sqlteacher.application.collaboration.AdminAuditPage;
import com.sqlteacher.application.collaboration.AdminHealthSummary;
import com.sqlteacher.application.collaboration.AdminOperationRejectedException;
import com.sqlteacher.application.collaboration.AdminUserSummary;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.RetentionCategory;
import com.sqlteacher.application.collaboration.RetentionJob;
import com.sqlteacher.application.collaboration.RetentionPreview;
import com.sqlteacher.application.collaboration.UserRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Set;
import java.util.UUID;

/**
 * Cloud administrator console persistence (v3.4.0 REF-2): health and user administration, the
 * admin audit query, W6 retention preview/execute/restore with confirmation tokens and safety
 * backups, and W6.3 token/backup cleanup. Behavior is unchanged from the former
 * {@code CloudStore} inner class of {@link SqlTeacherCloudServer}.
 */
final class CloudAdministrationStore extends CloudStoreBase {
    // Keeps the historical log category of the former SqlTeacherCloudServer nested store so
    // existing production log tooling is unaffected by the v3.4.0 file split.
    private static final Logger log = LoggerFactory.getLogger(SqlTeacherCloudServer.class);
    private static final ObjectMapper JSON = CloudJsonStoreSupport.mapper();

    CloudAdministrationStore(Path database) throws SQLException, IOException {
        super(database);
    }

    /**
     * v3.7.0 TFB-S4: automatic retention for synced learning events. Deletes (without archive)
     * sync_events older than the cutoff; the daily daemon in the server logs the removed count.
     */
    int autoPurgeSyncEvents(java.time.Instant cutoff) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "delete from sync_events where occurred_at < ?")) {
            statement.setString(1, cutoff.toString());
            return statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Cloud database operation failed", error);
        }
    }

    AdminHealthSummary adminHealth(AuthenticatedUser actor) {
        requireAdmin(actor);
        try (Connection connection = open()) {
            return new AdminHealthSummary(
                count(connection, "select count(*) from users where disabled=0"),
                count(connection, "select count(*) from users where disabled<>0"),
                count(connection, "select count(*) from access_tokens where revoked_at is null and expires_at>'"
                    + Instant.now() + "'"),
                count(connection, "select count(*) from refresh_tokens where revoked_at is null and expires_at>'"
                    + Instant.now() + "'"),
                count(connection, "select count(*) from class_assignments"),
                count(connection, "select count(*) from assignment_submissions"),
                Instant.now()
            );
        } catch (SQLException error) { throw database(error); }
    }

    List<AdminUserSummary> adminUsers(AuthenticatedUser actor) {
        requireAdmin(actor);
        try (Connection connection = open()) {
            // One grouped role query instead of one role query per user row.
            Map<String, Set<UserRole>> rolesByUser = new java.util.HashMap<>();
            try (PreparedStatement roles = connection.prepareStatement(
                "select user_id, role from user_roles order by user_id, role");
                 ResultSet roleRows = roles.executeQuery()) {
                while (roleRows.next()) {
                    rolesByUser
                        .computeIfAbsent(roleRows.getString("user_id"), key -> new java.util.LinkedHashSet<>())
                        .add(UserRole.valueOf(roleRows.getString("role")));
                }
            }
            List<AdminUserSummary> users = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "select id,email,display_name,disabled,created_at from users order by created_at,id")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) users.add(adminUser(rows, rolesByUser));
                }
            }
            return List.copyOf(users);
        } catch (SQLException error) { throw database(error); }
    }

    AdminUserSummary setUserDisabled(AuthenticatedUser actor, String userId, boolean disabled,
                                     String reasonCode) {
        requireAdmin(actor);
        String reason = validateReasonCode(reasonCode);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            AdminUserSummary target = adminUser(connection, userId);
            if (disabled && target.roles().contains(UserRole.ADMIN) && !target.disabled()
                && activeAdminCount(connection) <= 1) {
                audit(connection, actor.id(), "ADMIN_USER_DISABLE", "USER", userId, "DENIED",
                    "LAST_ADMIN_PROTECTED");
                connection.commit();
                throw new AdminOperationRejectedException(
                    "LAST_ADMIN_PROTECTED", "The final active administrator cannot be disabled");
            }
            try (PreparedStatement update = connection.prepareStatement(
                "update users set disabled=? where id=?")) {
                update.setInt(1, disabled ? 1 : 0);
                update.setString(2, userId);
                update.executeUpdate();
            }
            if (disabled) revokeAllSessions(connection, userId);
            audit(connection, actor.id(), disabled ? "ADMIN_USER_DISABLE" : "ADMIN_USER_RESTORE",
                "USER", userId, "SUCCESS", reason);
            connection.commit();
            return adminUser(connection, userId);
        } catch (SQLException error) { throw database(error); }
    }

    void revokeUserSessions(AuthenticatedUser actor, String userId, String reasonCode) {
        requireAdmin(actor);
        String reason = validateReasonCode(reasonCode);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            adminUser(connection, userId);
            revokeAllSessions(connection, userId);
            audit(connection, actor.id(), "ADMIN_SESSION_REVOKE_ALL", "USER", userId, "SUCCESS", reason);
            connection.commit();
        } catch (SQLException error) { throw database(error); }
    }

    // ---- v3.8.0 ACC-S4 one-time teacher role grant codes (decision point 1: plan B) ----

    private static final int ROLE_CODE_LENGTH = 8;
    private static final String ROLE_CODE_INVALID = "role code is invalid or has expired";

    /**
     * Mints a one-time teacher upgrade code. The plaintext appears exactly once, in this return
     * value; the database keeps only its SHA-256 hash. Issuance, list and revoke are admin-only
     * and audited; redemption (see {@link #redeemRoleCode}) is a self-service account operation.
     */
    TeacherRoleCodeIssued issueTeacherRoleCode(AuthenticatedUser actor, int ttlDays) {
        requireAdmin(actor);
        if (ttlDays < 1 || ttlDays > 365) throw new IllegalArgumentException("ttlDays must be 1 to 365");
        String code = Hashes.randomCode(ROLE_CODE_LENGTH);
        byte[] hash = tokenHash(code);
        String hashHex = HexFormat.of().formatHex(hash);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlDays, ChronoUnit.DAYS);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into role_grant_codes(code_hash,role,created_by,created_at,expires_at) values(?,?,?,?,?)")) {
            statement.setBytes(1, hash);
            statement.setString(2, "TEACHER");
            statement.setString(3, actor.id());
            statement.setString(4, now.toString());
            statement.setString(5, expiresAt.toString());
            statement.executeUpdate();
            audit(connection, actor.id(), "ADMIN_ROLE_CODE_ISSUE", "ROLE_GRANT_CODE", hashHex, "SUCCESS", "TEACHER");
        } catch (SQLException error) { throw database(error); }
        return new TeacherRoleCodeIssued(code, hashHex, "TEACHER", expiresAt);
    }

    List<TeacherRoleCodeView> listTeacherRoleCodes(AuthenticatedUser actor) {
        requireAdmin(actor);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select code_hash,role,created_at,expires_at,used_by,used_at,revoked_at from role_grant_codes "
                + "order by created_at desc")) {
            List<TeacherRoleCodeView> codes = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    codes.add(new TeacherRoleCodeView(
                        HexFormat.of().formatHex(rows.getBytes("code_hash")),
                        rows.getString("role"),
                        Instant.parse(rows.getString("created_at")),
                        Instant.parse(rows.getString("expires_at")),
                        rows.getString("used_by"),
                        rows.getString("used_at") == null ? null : Instant.parse(rows.getString("used_at")),
                        rows.getString("revoked_at") == null ? null : Instant.parse(rows.getString("revoked_at"))));
                }
            }
            return List.copyOf(codes);
        } catch (SQLException error) { throw database(error); }
    }

    void revokeTeacherRoleCode(AuthenticatedUser actor, String codeHashHex) {
        requireAdmin(actor);
        byte[] hash = hexCodeHash(codeHashHex);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                    "update role_grant_codes set revoked_at=? where code_hash=? and revoked_at is null and used_at is null")) {
                    statement.setString(1, Instant.now().toString());
                    statement.setBytes(2, hash);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("role code does not exist or can no longer be revoked");
                    }
                }
                audit(connection, actor.id(), "ADMIN_ROLE_CODE_REVOKE", "ROLE_GRANT_CODE",
                    HexFormat.of().formatHex(hash), "SUCCESS", "ADMIN_REQUEST");
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) { throw database(error); }
    }

    /**
     * Redeems a one-time teacher upgrade code for the signed-in account. Every failure mode
     * (unknown, wrong, expired, used, revoked) collapses into one uniform message; a failed
     * redemption never consumes the code or changes roles.
     */
    void redeemRoleCode(AuthenticatedUser actor, String code) {
        if (actor.hasRole(UserRole.TEACHER)) {
            throw new IllegalArgumentException("account already has the teacher role");
        }
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() != ROLE_CODE_LENGTH) throw new IllegalArgumentException(ROLE_CODE_INVALID);
        byte[] hash = tokenHash(normalized);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement find = connection.prepareStatement(
                    "select expires_at,used_at,revoked_at from role_grant_codes where code_hash=?")) {
                    find.setBytes(1, hash);
                    try (ResultSet row = find.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException(ROLE_CODE_INVALID);
                        if (row.getString("used_at") != null || row.getString("revoked_at") != null
                            || Instant.parse(row.getString("expires_at")).isBefore(Instant.now())) {
                            throw new IllegalArgumentException(ROLE_CODE_INVALID);
                        }
                    }
                }
                try (PreparedStatement consume = connection.prepareStatement(
                    "update role_grant_codes set used_by=?,used_at=? where code_hash=? and used_at is null and revoked_at is null")) {
                    consume.setString(1, actor.id());
                    consume.setString(2, Instant.now().toString());
                    consume.setBytes(3, hash);
                    if (consume.executeUpdate() != 1) throw new IllegalArgumentException(ROLE_CODE_INVALID);
                }
                try (PreparedStatement role = connection.prepareStatement(
                    "insert or ignore into user_roles(user_id,role) values(?, 'TEACHER')")) {
                    role.setString(1, actor.id());
                    role.executeUpdate();
                }
                audit(connection, actor.id(), "AUTH_ROLE_CODE_REDEEM", "ROLE_GRANT_CODE",
                    HexFormat.of().formatHex(hash), "SUCCESS", "TEACHER");
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) { throw database(error); }
    }

    private static byte[] hexCodeHash(String hex) {
        try {
            return HexFormat.of().parseHex(hex == null ? "" : hex.strip());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("role code hash is invalid");
        }
    }

    AdminAuditPage adminAudit(AuthenticatedUser actor, String action, Instant from, Instant to,
                              int page, int pageSize) {
        requireAdmin(actor);
        if (action != null && !action.isBlank() && !action.matches("[A-Z0-9_]{3,80}")) {
            throw new IllegalArgumentException("action is invalid");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (page < 0 || pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("Invalid audit pagination");
        }
        StringBuilder where = new StringBuilder(" where 1=1");
        List<String> parameters = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" and action=?");
            parameters.add(action);
        }
        if (from != null) {
            where.append(" and created_at>=?");
            parameters.add(from.toString());
        }
        if (to != null) {
            where.append(" and created_at<=?");
            parameters.add(to.toString());
        }
        try (Connection connection = open()) {
            int totalRows;
            try (PreparedStatement count = connection.prepareStatement(
                "select count(*) from admin_audit" + where)) {
                bind(count, parameters);
                try (ResultSet row = count.executeQuery()) { totalRows = row.getInt(1); }
            }
            List<AdminAuditEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "select id,actor_user_id,action,target_type,target_id,result,reason_code,correlation_id,created_at "
                    + "from admin_audit" + where + " order by created_at desc,id desc limit ? offset ?")) {
                int parameter = bind(statement, parameters);
                statement.setInt(parameter++, pageSize);
                statement.setLong(parameter, (long) page * pageSize);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) entries.add(new AdminAuditEntry(
                        rows.getString("id"), rows.getString("actor_user_id"), rows.getString("action"),
                        rows.getString("target_type"), rows.getString("target_id"), rows.getString("result"),
                        rows.getString("reason_code"), rows.getString("correlation_id"),
                        Instant.parse(rows.getString("created_at"))
                    ));
                }
            }
            return new AdminAuditPage(entries, page, pageSize, totalRows);
        } catch (SQLException error) { throw database(error); }
    }

    private int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            return row.getInt(1);
        }
    }

    RetentionPreview previewRetention(AuthenticatedUser actor, RetentionCategory category, Instant cutoff) {
        requireAdmin(actor);
        if (category == null || cutoff == null) {
            throw new IllegalArgumentException("Retention category and cutoff are required");
        }
        int minimumDays = minimumRetentionDays(category);
        if (cutoff.isAfter(Instant.now().minus(minimumDays, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException(
                "Retention cutoff must preserve at least " + minimumDays + " days for " + category);
        }
        RetentionSpec spec = retentionSpec(category);
        String id = UUID.randomUUID().toString();
        String confirmationToken = Base64.getUrlEncoder().withoutPadding().encodeToString(Hashes.randomBytes(24));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(15, ChronoUnit.MINUTES);
        try (Connection connection = open()) {
            int affectedRows = retentionCount(connection, spec, cutoff);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into retention_jobs(id,category,cutoff,preview_count,affected_count,status,"
                    + "confirmation_hash,expires_at,created_at,actor_user_id) values(?,?,?,?,0,'PREVIEWED',?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, category.name());
                statement.setString(3, cutoff.toString());
                statement.setInt(4, affectedRows);
                statement.setBytes(5, tokenHash(confirmationToken));
                statement.setString(6, expiresAt.toString());
                statement.setString(7, now.toString());
                statement.setString(8, actor.id());
                statement.executeUpdate();
            }
            audit(connection, actor.id(), "ADMIN_RETENTION_PREVIEW", "RETENTION_JOB", id,
                "SUCCESS", category.name());
            return new RetentionPreview(id, category, cutoff, affectedRows, expiresAt, confirmationToken);
        } catch (SQLException error) { throw database(error); }
    }

    RetentionJob executeRetention(AuthenticatedUser actor, String previewId, String confirmationToken,
                                  String backupReference) {
        requireAdmin(actor);
        validateUuid(previewId, "previewId");
        if (confirmationToken == null || confirmationToken.isBlank()) {
            throw new IllegalArgumentException("confirmationToken is required");
        }
        if (backupReference == null || !backupReference.matches("[A-Za-z0-9._:/-]{3,200}")) {
            throw new IllegalArgumentException("A valid backupReference is required");
        }
        try (Connection validation = open()) {
            RetentionJobState state = retentionJobState(validation, previewId);
            if (!"PREVIEWED".equals(state.status()) || Instant.now().isAfter(state.expiresAt())
                || !Hashes.constantTimeEquals(state.confirmationHash(), tokenHash(confirmationToken))) {
                audit(validation, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                    "DENIED", "INVALID_OR_EXPIRED_CONFIRMATION");
                throw new AdminOperationRejectedException("RETENTION_CONFIRMATION_INVALID",
                    "Retention preview confirmation is invalid or expired");
            }
        } catch (SQLException error) { throw database(error); }
        String safetyBackup = createRetentionBackup(previewId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            RetentionJobState state = retentionJobState(connection, previewId);
            if (!"PREVIEWED".equals(state.status()) || Instant.now().isAfter(state.expiresAt())
                || !Hashes.constantTimeEquals(state.confirmationHash(), tokenHash(confirmationToken))) {
                audit(connection, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                    "DENIED", "INVALID_OR_EXPIRED_CONFIRMATION");
                connection.commit();
                throw new AdminOperationRejectedException("RETENTION_CONFIRMATION_INVALID",
                    "Retention preview confirmation is invalid or expired");
            }
            try (PreparedStatement lock = connection.prepareStatement(
                "update retention_jobs set status='EXECUTING' where id=? and status='PREVIEWED'")) {
                lock.setString(1, previewId);
                if (lock.executeUpdate() != 1) throw new SQLException("Retention job state changed concurrently");
            }
            RetentionSpec spec = retentionSpec(state.category());
            int currentRows = retentionCount(connection, spec, state.cutoff());
            if (currentRows != state.previewRows()) {
                try (PreparedStatement update = connection.prepareStatement(
                    "update retention_jobs set status='BLOCKED' where id=?")) {
                    update.setString(1, previewId);
                    update.executeUpdate();
                }
                audit(connection, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                    "DENIED", "SCOPE_CHANGED");
                connection.commit();
                throw new AdminOperationRejectedException("RETENTION_SCOPE_CHANGED",
                    "Retention scope changed after preview; create a new preview");
            }
            int archivedRows = archiveRetentionRows(connection, previewId, spec, state.cutoff());
            int deletedRows = deleteRetentionRows(connection, spec, state.cutoff());
            if (archivedRows != currentRows || deletedRows != currentRows) {
                throw new SQLException("Retention archive and delete counts did not match preview");
            }
            Instant executedAt = Instant.now();
            try (PreparedStatement update = connection.prepareStatement(
                "update retention_jobs set status='COMPLETED',affected_count=?,backup_reference=?,safety_backup=?,"
                    + "executed_at=? "
                    + "where id=?")) {
                update.setInt(1, deletedRows);
                update.setString(2, backupReference);
                update.setString(3, safetyBackup);
                update.setString(4, executedAt.toString());
                update.setString(5, previewId);
                update.executeUpdate();
            }
            audit(connection, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                "SUCCESS", state.category().name());
            connection.commit();
            return retentionJob(connection, previewId);
        } catch (SQLException error) { throw database(error); }
    }

    private String createRetentionBackup(String jobId) {
        Path backupDirectory = database.getParent().resolve("retention-backups");
        Path backup = backupDirectory.resolve("retention-" + jobId + ".db").toAbsolutePath().normalize();
        try {
            Files.createDirectories(backupDirectory);
            String escapedPath = backup.toString().replace("'", "''");
            try (Connection source = open(); Statement statement = source.createStatement()) {
                statement.executeUpdate("vacuum into '" + escapedPath + "'");
            }
            try (Connection verification = DriverManager.getConnection("jdbc:sqlite:" + backup);
                 Statement statement = verification.createStatement();
                 ResultSet result = statement.executeQuery("pragma integrity_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Retention safety backup failed integrity verification");
                }
            }
            return backup.getFileName().toString();
        } catch (IOException | SQLException error) {
            throw new AdminOperationRejectedException("RETENTION_BACKUP_FAILED",
                "Retention safety backup could not be created and verified");
        }
    }

    RetentionJob restoreRetention(AuthenticatedUser actor, String jobId) {
        requireAdmin(actor);
        validateUuid(jobId, "jobId");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            RetentionJobState state = retentionJobState(connection, jobId);
            if (!"COMPLETED".equals(state.status())) {
                audit(connection, actor.id(), "ADMIN_RETENTION_RESTORE", "RETENTION_JOB", jobId,
                    "DENIED", "JOB_NOT_RESTORABLE");
                connection.commit();
                throw new AdminOperationRejectedException("RETENTION_NOT_RESTORABLE",
                    "Only a completed retention job can be restored");
            }
            int restored = restoreRetentionRows(connection, jobId, retentionSpec(state.category()));
            if (restored != state.affectedRows()) {
                throw new SQLException("Restored row count did not match the completed job");
            }
            Instant restoredAt = Instant.now();
            try (PreparedStatement update = connection.prepareStatement(
                "update retention_jobs set status='RESTORED',restored_at=? where id=?")) {
                update.setString(1, restoredAt.toString());
                update.setString(2, jobId);
                update.executeUpdate();
            }
            audit(connection, actor.id(), "ADMIN_RETENTION_RESTORE", "RETENTION_JOB", jobId,
                "SUCCESS", state.category().name());
            connection.commit();
            return retentionJob(connection, jobId);
        } catch (SQLException error) { throw database(error); }
    }

    private int retentionCount(Connection connection, RetentionSpec spec, Instant cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select count(*) from " + spec.table() + " where " + spec.timeColumn() + "<?")) {
            statement.setString(1, cutoff.toString());
            try (ResultSet row = statement.executeQuery()) { return row.getInt(1); }
        }
    }

    private int archiveRetentionRows(Connection connection, String jobId, RetentionSpec spec, Instant cutoff)
        throws SQLException {
        String columns = String.join(",", spec.columns());
        int archived = 0;
        try (PreparedStatement select = connection.prepareStatement(
            "select " + columns + " from " + spec.table() + " where " + spec.timeColumn() + "<?");
             PreparedStatement insert = connection.prepareStatement(
                 "insert into retention_archive(job_id,category,row_key,payload_json,archived_at) values(?,?,?,?,?)")) {
            select.setString(1, cutoff.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    for (String column : spec.columns()) payload.put(column, rows.getObject(column));
                    insert.setString(1, jobId);
                    insert.setString(2, spec.category().name());
                    insert.setString(3, String.valueOf(rows.getObject(spec.keyColumn())));
                    insert.setString(4, JSON.writeValueAsString(payload));
                    insert.setString(5, Instant.now().toString());
                    insert.addBatch();
                    archived++;
                }
            } catch (IOException error) { throw new SQLException("Could not archive retention payload", error); }
            insert.executeBatch();
        }
        return archived;
    }

    private int deleteRetentionRows(Connection connection, RetentionSpec spec, Instant cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "delete from " + spec.table() + " where " + spec.timeColumn() + "<?")) {
            statement.setString(1, cutoff.toString());
            return statement.executeUpdate();
        }
    }

    private int restoreRetentionRows(Connection connection, String jobId, RetentionSpec spec) throws SQLException {
        String columns = String.join(",", spec.columns());
        String placeholders = String.join(",", java.util.Collections.nCopies(spec.columns().size(), "?"));
        int restored = 0;
        try (PreparedStatement archive = connection.prepareStatement(
            "select payload_json from retention_archive where job_id=? order by row_key");
             PreparedStatement insert = connection.prepareStatement(
                 "insert into " + spec.table() + "(" + columns + ") values(" + placeholders + ")")) {
            archive.setString(1, jobId);
            try (ResultSet rows = archive.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> payload;
                    try {
                        payload = JSON.readValue(rows.getString(1), new TypeReference<Map<String, Object>>() { });
                    } catch (IOException error) {
                        throw new SQLException("Could not read retention archive payload", error);
                    }
                    int index = 1;
                    for (String column : spec.columns()) insert.setObject(index++, payload.get(column));
                    insert.addBatch();
                    restored++;
                }
            }
            insert.executeBatch();
        }
        return restored;
    }

    private RetentionJobState retentionJobState(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select category,cutoff,preview_count,affected_count,status,confirmation_hash,expires_at "
                + "from retention_jobs where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Retention job was not found");
                return new RetentionJobState(RetentionCategory.valueOf(row.getString("category")),
                    Instant.parse(row.getString("cutoff")), row.getInt("preview_count"),
                    row.getInt("affected_count"), row.getString("status"),
                    row.getBytes("confirmation_hash"), Instant.parse(row.getString("expires_at")));
            }
        }
    }

    private RetentionJob retentionJob(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select category,cutoff,preview_count,affected_count,status,backup_reference,created_at,executed_at,"
                + "restored_at from retention_jobs where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Retention job was not found");
                return new RetentionJob(id, RetentionCategory.valueOf(row.getString("category")),
                    Instant.parse(row.getString("cutoff")), row.getInt("preview_count"),
                    row.getInt("affected_count"), row.getString("status"), row.getString("backup_reference"),
                    Instant.parse(row.getString("created_at")), instantOrNull(row.getString("executed_at")),
                    instantOrNull(row.getString("restored_at")));
            }
        }
    }

    private RetentionSpec retentionSpec(RetentionCategory category) {
        return switch (category) {
            case SYNC_EVENTS -> new RetentionSpec(category, "sync_events", "version", "occurred_at",
                List.of("version", "user_id", "event_id", "event_type", "payload_json", "occurred_at"));
            case ASSIGNMENT_SUBMISSIONS -> new RetentionSpec(category, "assignment_submissions", "id",
                "submitted_at", List.of("id", "operation_id", "classroom_id", "assignment_id", "user_id",
                "attempt_number", "status", "result_hash", "error_code", "client_completed_at", "submitted_at"));
            case ADMIN_AUDIT -> new RetentionSpec(category, "admin_audit", "id", "created_at",
                List.of("id", "actor_user_id", "action", "target_type", "target_id", "result", "reason_code",
                    "correlation_id", "created_at"));
            case EXPORT_AUDIT -> new RetentionSpec(category, "export_audit", "id", "created_at",
                List.of("id", "user_id", "classroom_id", "row_count", "created_at", "assignment_id",
                    "export_type", "filter_summary"));
        };
    }

    private int minimumRetentionDays(RetentionCategory category) {
        return switch (category) {
            case SYNC_EVENTS -> 180;
            case ASSIGNMENT_SUBMISSIONS, ADMIN_AUDIT, EXPORT_AUDIT -> 365;
        };
    }

    private void validateUuid(String value, String name) {
        if (value == null || !value.matches("[a-fA-F0-9-]{36}")) {
            throw new IllegalArgumentException(name + " must be a UUID");
        }
    }

    private AdminUserSummary adminUser(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select id,email,display_name,disabled,created_at from users where id=?")) {
            statement.setString(1, userId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("User was not found");
                return adminUser(connection, row);
            }
        }
    }

    private AdminUserSummary adminUser(ResultSet row, Map<String, Set<UserRole>> rolesByUser) throws SQLException {
        return new AdminUserSummary(row.getString("id"), row.getString("email"), row.getString("display_name"),
            rolesByUser.getOrDefault(row.getString("id"), Set.of()),
            row.getInt("disabled") != 0, Instant.parse(row.getString("created_at")));
    }

    private AdminUserSummary adminUser(Connection connection, ResultSet row) throws SQLException {
        Set<UserRole> roles = new java.util.HashSet<>();
        try (PreparedStatement roleQuery = connection.prepareStatement(
            "select role from user_roles where user_id=? order by role")) {
            roleQuery.setString(1, row.getString("id"));
            try (ResultSet roleRows = roleQuery.executeQuery()) {
                while (roleRows.next()) roles.add(UserRole.valueOf(roleRows.getString("role")));
            }
        }
        return new AdminUserSummary(row.getString("id"), row.getString("email"), row.getString("display_name"),
            roles, row.getInt("disabled") != 0, Instant.parse(row.getString("created_at")));
    }

    private int activeAdminCount(Connection connection) throws SQLException {
        return count(connection, "select count(*) from users u join user_roles r on r.user_id=u.id "
            + "where u.disabled=0 and r.role='ADMIN'");
    }

    private void revokeAllSessions(Connection connection, String userId) throws SQLException {
        Instant now = Instant.now();
        for (String table : List.of("access_tokens", "refresh_tokens")) {
            try (PreparedStatement statement = connection.prepareStatement(
                "update " + table + " set revoked_at=? where user_id=? and revoked_at is null")) {
                statement.setString(1, now.toString());
                statement.setString(2, userId);
                statement.executeUpdate();
            }
        }
    }

    private String validateReasonCode(String reasonCode) {
        if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{3,64}")) {
            throw new IllegalArgumentException("reasonCode must contain only A-Z, 0-9, or underscore");
        }
        return reasonCode;
    }

    private static final String[] CLEANUP_TARGETS = {"auth-tokens", "retention-backups"};

    private int cleanupRetentionDays(String target, int override) {
        if (override > 0) return override;
        String configured;
        if ("auth-tokens".equals(target)) {
            configured = System.getenv("SQLTEACHER_CLOUD_TOKEN_RETENTION_DAYS");
            return configured == null ? 30 : Integer.parseInt(configured);
        }
        configured = System.getenv("SQLTEACHER_CLOUD_BACKUP_RETENTION_DAYS");
        return configured == null ? 90 : Integer.parseInt(configured);
    }

    /** W6.3 dry-run: reports how many expired tokens or stale backup files would go. */
    Map<String, Object> cleanupPreview(AuthenticatedUser actor, String target, int daysOverride) {
        requireAdmin(actor);
        if (!java.util.Arrays.asList(CLEANUP_TARGETS).contains(target)) {
            throw new IllegalArgumentException("Unknown cleanup target: " + target);
        }
        int days = cleanupRetentionDays(target, daysOverride);
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(days));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("target", target);
        result.put("retentionDays", days);
        result.put("cutoff", cutoff.toString());
        if ("auth-tokens".equals(target)) {
            try (Connection connection = open();
                 PreparedStatement access = connection.prepareStatement(
                     "select count(*) from access_tokens where expires_at<? or (revoked_at is not null and revoked_at<?)");
                 PreparedStatement refresh = connection.prepareStatement(
                     "select count(*) from refresh_tokens where expires_at<? or (revoked_at is not null and revoked_at<?)")) {
                access.setString(1, cutoff.toString());
                access.setString(2, cutoff.toString());
                refresh.setString(1, cutoff.toString());
                refresh.setString(2, cutoff.toString());
                try (ResultSet rows = access.executeQuery()) {
                    rows.next();
                    result.put("accessTokens", rows.getInt(1));
                }
                try (ResultSet rows = refresh.executeQuery()) {
                    rows.next();
                    result.put("refreshTokens", rows.getInt(1));
                }
            } catch (SQLException error) { throw database(error); }
        } else {
            result.put("files", staleRetentionBackups(cutoff).size());
        }
        try (Connection connection = open()) {
            audit(connection, actor.id(), "ADMIN_CLEANUP_PREVIEW", "CLEANUP", target,
                "SUCCESS", "days=" + days);
        } catch (SQLException error) { throw database(error); }
        return result;
    }

    /** W6.3 execute: deletes the rows/files reported by the matching preview. */
    Map<String, Object> cleanupExecute(AuthenticatedUser actor, String target, int daysOverride) {
        requireAdmin(actor);
        if (!java.util.Arrays.asList(CLEANUP_TARGETS).contains(target)) {
            throw new IllegalArgumentException("Unknown cleanup target: " + target);
        }
        int days = cleanupRetentionDays(target, daysOverride);
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(days));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("target", target);
        result.put("retentionDays", days);
        if ("auth-tokens".equals(target)) {
            try (Connection connection = open()) {
                int removed = 0;
                for (String table : List.of("access_tokens", "refresh_tokens")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                        "delete from " + table + " where expires_at<? or (revoked_at is not null and revoked_at<?)")) {
                        statement.setString(1, cutoff.toString());
                        statement.setString(2, cutoff.toString());
                        removed += statement.executeUpdate();
                    }
                }
                result.put("removed", removed);
                audit(connection, actor.id(), "ADMIN_CLEANUP_EXECUTE", "CLEANUP", target,
                    "SUCCESS", "removed=" + removed);
            } catch (SQLException error) { throw database(error); }
        } else {
            int removed = 0;
            for (Path file : staleRetentionBackups(cutoff)) {
                try {
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException error) {
                    log.warn("Could not delete stale retention backup {}", file, error);
                }
            }
            result.put("removed", removed);
            try (Connection connection = open()) {
                audit(connection, actor.id(), "ADMIN_CLEANUP_EXECUTE", "CLEANUP", target,
                    "SUCCESS", "removed=" + removed);
            } catch (SQLException ignored) {
            }
        }
        return result;
    }

    private List<Path> staleRetentionBackups(Instant cutoff) {
        Path directory = database.getParent().resolve("retention-backups");
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> stale = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(file -> file.getFileName().toString().startsWith("retention-")
                    && file.getFileName().toString().endsWith(".db"))
                .forEach(file -> {
                    try {
                        if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) stale.add(file);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
        return List.copyOf(stale);
    }

    private int bind(PreparedStatement statement, List<String> parameters) throws SQLException {
        int index = 1;
        for (String parameter : parameters) statement.setString(index++, parameter);
        return index;
    }

    private void requireAdmin(AuthenticatedUser actor) {
        if (!actor.hasRole(UserRole.ADMIN)) throw new SecurityException("administrator role required");
    }

    private Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return Instant.parse(value);
    }

    private record RetentionSpec(RetentionCategory category, String table, String keyColumn,
                                 String timeColumn, List<String> columns) { }
    private record RetentionJobState(RetentionCategory category, Instant cutoff, int previewRows,
                                     int affectedRows, String status, byte[] confirmationHash,
                                     Instant expiresAt) { }
}
