package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.ExerciseBankContent;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server-side store for the distributed exercise banks (v3.3 W4.2/W4.5). Content arrives
 * as a validated text-DSL package and is published per channel as a new monotonically
 * increasing bank version. Every version keeps a full block snapshot so an administrator
 * can roll the active version back for all clients at once. Reads (manifest and blocks)
 * are public; publishing and rolling back require the administrator role and are audited.
 */
final class V31ExerciseBankStore {
    static final String DEFAULT_CHANNEL = "network";
    private static final int EXPECTED_SCHEMA = 2;
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private final Path database;
    private final ExerciseBankContent content = new ExerciseBankContent();

    V31ExerciseBankStore(Path database) throws SQLException {
        this.database = Objects.requireNonNull(database, "database must not be null").toAbsolutePath().normalize();
        initialize();
    }

    synchronized int publish(AuthenticatedUser actor, String packageText) {
        return publish(actor, DEFAULT_CHANNEL, packageText);
    }

    synchronized int publish(AuthenticatedUser actor, String channel, String packageText) {
        requireAdmin(actor);
        String normalizedChannel = normalizeChannel(channel);
        ExerciseBankContent.ParsedBank bank;
        try {
            bank = content.parse(packageText);
        } catch (SqlTeacherException | IllegalArgumentException error) {
            throw new SqlTeacherException("EXERCISE_BANK_INVALID", "题库包无法解析：" + error.getMessage());
        }
        List<String> failures = content.selfTestFailures(bank);
        if (!failures.isEmpty()) {
            throw new SqlTeacherException(
                "EXERCISE_BANK_INVALID", "题库包未通过自测：" + String.join("；", failures)
            );
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                int bankVersion = currentVersion(connection, normalizedChannel) + 1;
                try (PreparedStatement delete = connection.prepareStatement(
                    "delete from exercise_bank_blocks where channel = ?"
                )) {
                    delete.setString(1, normalizedChannel);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                    "insert into exercise_bank_blocks(channel, block_type, block_id, version, content, sha256, bank_version)"
                        + " values (?, ?, ?, ?, ?, ?, ?)"
                )) {
                    for (var dataset : bank.datasets()) {
                        bindBlock(insert, normalizedChannel, "DATASET", dataset.id(), dataset.version(),
                            content.encodeDatasetBlock(dataset), bankVersion);
                        insert.addBatch();
                    }
                    for (var exercise : bank.exercises()) {
                        bindBlock(insert, normalizedChannel, "EXERCISE", exercise.id(), exercise.version(),
                            content.encodeExerciseBlock(exercise), bankVersion);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                snapshotHistory(connection, normalizedChannel, bankVersion);
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into exercise_bank_state(channel, bank_version, updated_at) values (?, ?, ?)"
                        + " on conflict(channel) do update set bank_version = excluded.bank_version,"
                        + " updated_at = excluded.updated_at"
                )) {
                    statement.setString(1, normalizedChannel);
                    statement.setInt(2, bankVersion);
                    statement.setString(3, Instant.now().toString());
                    statement.executeUpdate();
                }
                audit(connection, actor, "EXERCISE_BANK_PUBLISH", normalizedChannel,
                    String.valueOf(bankVersion), "SUCCESS",
                    bank.datasets().size() + " datasets, " + bank.exercises().size() + " exercises");
                connection.commit();
                return bankVersion;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_PUBLISH_FAILED", "Failed to publish the exercise bank.", error);
        }
    }

    /** Restores the active blocks from a historical version snapshot; effective immediately. */
    synchronized int rollback(AuthenticatedUser actor, String channel, int bankVersion) {
        requireAdmin(actor);
        String normalizedChannel = normalizeChannel(channel);
        if (bankVersion < 1) {
            throw new SqlTeacherException("EXERCISE_BANK_INVALID", "题库版本无效。");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Integer snapshotVersion = null;
                try (PreparedStatement statement = connection.prepareStatement(
                    "select bank_version from exercise_bank_history"
                        + " where channel = ? and bank_version = ?"
                )) {
                    statement.setString(1, normalizedChannel);
                    statement.setInt(2, bankVersion);
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) {
                            snapshotVersion = row.getInt(1);
                        }
                    }
                }
                if (snapshotVersion == null) {
                    throw new SqlTeacherException("EXERCISE_BANK_INVALID", "目标版本不存在或未保留快照。");
                }
                try (PreparedStatement delete = connection.prepareStatement(
                    "delete from exercise_bank_blocks where channel = ?"
                )) {
                    delete.setString(1, normalizedChannel);
                    delete.executeUpdate();
                }
                try (PreparedStatement copy = connection.prepareStatement(
                    "insert into exercise_bank_blocks(channel, block_type, block_id, version, content, sha256, bank_version)"
                        + " select channel, block_type, block_id, version, content, sha256, bank_version"
                        + " from exercise_bank_history_blocks where channel = ? and bank_version = ?"
                )) {
                    copy.setString(1, normalizedChannel);
                    copy.setInt(2, bankVersion);
                    copy.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "update exercise_bank_state set bank_version = ?, updated_at = ? where channel = ?"
                )) {
                    statement.setInt(1, bankVersion);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, normalizedChannel);
                    statement.executeUpdate();
                }
                audit(connection, actor, "EXERCISE_BANK_ROLLBACK", normalizedChannel,
                    String.valueOf(bankVersion), "SUCCESS", "rolled back for all clients");
                connection.commit();
                return bankVersion;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_ROLLBACK_FAILED", "Failed to roll back the exercise bank.", error);
        }
    }

    /** Channels known to this server with their active versions, for client subscriptions. */
    List<Map<String, Object>> channels() {
        try (Connection connection = open();
             ResultSet rows = connection.createStatement().executeQuery(
                 "select channel, bank_version, updated_at from exercise_bank_state order by channel")) {
            List<Map<String, Object>> result = new ArrayList<>();
            while (rows.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("channel", rows.getString("channel"));
                item.put("bankVersion", rows.getInt("bank_version"));
                item.put("updatedAt", rows.getString("updated_at"));
                result.add(item);
            }
            return result;
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_READ_FAILED", "Failed to list the exercise bank channels.", error);
        }
    }

    Map<String, Object> manifest() {
        return manifest(DEFAULT_CHANNEL);
    }

    Map<String, Object> manifest(String channel) {
        String normalizedChannel = normalizeChannel(channel);
        try (Connection connection = open()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("channel", normalizedChannel);
            result.put("bankVersion", currentVersion(connection, normalizedChannel));
            result.put("datasets", blockRefs(connection, normalizedChannel, "DATASET"));
            result.put("exercises", blockRefs(connection, normalizedChannel, "EXERCISE"));
            return result;
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_READ_FAILED", "Failed to read the exercise bank.", error);
        }
    }

    Map<String, Object> block(String type, String id) {
        return block(DEFAULT_CHANNEL, type, id);
    }

    Map<String, Object> block(String channel, String type, String id) {
        String normalizedChannel = normalizeChannel(channel);
        if (!"DATASET".equals(type) && !"EXERCISE".equals(type)) {
            throw new SqlTeacherException("EXERCISE_BANK_INVALID", "Unknown exercise bank block type.");
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                 "select block_id, version, sha256, content from exercise_bank_blocks"
                     + " where channel = ? and block_type = ? and block_id = ?"
             )) {
            statement.setString(1, normalizedChannel);
            statement.setString(2, type);
            statement.setString(3, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", type);
                result.put("id", row.getString("block_id"));
                result.put("version", row.getInt("version"));
                result.put("sha256", row.getString("sha256"));
                result.put("content", row.getString("content"));
                return result;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_READ_FAILED", "Failed to read the exercise bank.", error);
        }
    }

    private static void bindBlock(
        PreparedStatement statement, String channel, String type, String id, int version, String fragment, int bankVersion
    ) throws SQLException {
        statement.setString(1, channel);
        statement.setString(2, type);
        statement.setString(3, id);
        statement.setInt(4, version);
        statement.setString(5, fragment);
        statement.setString(6, ExerciseBankHashing.sha256Hex(fragment));
        statement.setInt(7, bankVersion);
    }

    /** Keeps a full per-version block snapshot so rollback never depends on live rows. */
    private static void snapshotHistory(Connection connection, String channel, int bankVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into exercise_bank_history(channel, bank_version, created_at) values (?, ?, ?)"
        )) {
            statement.setString(1, channel);
            statement.setInt(2, bankVersion);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into exercise_bank_history_blocks(channel, bank_version, block_type, block_id, version, content, sha256)"
                + " select channel, ?, block_type, block_id, version, content, sha256"
                + " from exercise_bank_blocks where channel = ?"
        )) {
            statement.setInt(1, bankVersion);
            statement.setString(2, channel);
            statement.executeUpdate();
        }
    }

    private List<Map<String, Object>> blockRefs(Connection connection, String channel, String type) throws SQLException {
        List<Map<String, Object>> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "select block_id, version, sha256 from exercise_bank_blocks"
                + " where channel = ? and block_type = ? order by block_id"
        )) {
            statement.setString(1, channel);
            statement.setString(2, type);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("id", rows.getString("block_id"));
                    ref.put("version", rows.getInt("version"));
                    ref.put("sha256", rows.getString("sha256"));
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    private int currentVersion(Connection connection, String channel) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select bank_version from exercise_bank_state where channel = ?"
        )) {
            statement.setString(1, channel);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private static String normalizeChannel(String channel) {
        String normalized = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim().toLowerCase(java.util.Locale.ROOT);
        if (!CHANNEL_PATTERN.matcher(normalized).matches()) {
            throw new SqlTeacherException("EXERCISE_BANK_INVALID", "题库频道名无效（小写字母、数字与横线，最长 32 位）。");
        }
        return normalized;
    }

    private void audit(Connection connection, AuthenticatedUser actor, String action,
                       String channelId, String targetId, String result, String reasonCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into admin_audit(id,actor_user_id,action,target_type,target_id,result,reason_code,"
                + "correlation_id,created_at) values(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actor.id());
            statement.setString(3, action);
            statement.setString(4, "EXERCISE_BANK_CHANNEL");
            statement.setString(5, channelId);
            statement.setString(6, result);
            statement.setString(7, reasonCode);
            statement.setString(8, UUID.randomUUID().toString());
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static void requireAdmin(AuthenticatedUser actor) {
        if (actor == null || !actor.hasRole(UserRole.ADMIN)) {
            throw new SecurityException("administrator role required");
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            int schemaVersion = statement.executeQuery("pragma user_version").getInt(1);
            if (schemaVersion < EXPECTED_SCHEMA) {
                migrateFromLegacySchema(statement);
            }
            statement.executeUpdate("pragma user_version = " + EXPECTED_SCHEMA);
            statement.executeUpdate("""
                create table if not exists exercise_bank_blocks (
                    channel text not null,
                    block_type text not null,
                    block_id text not null,
                    version integer not null,
                    content text not null,
                    sha256 text not null,
                    bank_version integer not null,
                    primary key(channel, block_type, block_id)
                )
                """);
            statement.executeUpdate("""
                create table if not exists exercise_bank_state (
                    channel text primary key,
                    bank_version integer not null,
                    updated_at text not null
                )
                """);
            statement.executeUpdate("""
                create table if not exists exercise_bank_history (
                    channel text not null,
                    bank_version integer not null,
                    created_at text not null,
                    primary key(channel, bank_version)
                )
                """);
            statement.executeUpdate("""
                create table if not exists exercise_bank_history_blocks (
                    channel text not null,
                    bank_version integer not null,
                    block_type text not null,
                    block_id text not null,
                    version integer not null,
                    content text not null,
                    sha256 text not null,
                    primary key(channel, bank_version, block_type, block_id)
                )
                """);
            // Publish/rollback audit rows land in the shared admin_audit table; the DDL
            // matches the server's own so either initializer can run first.
            statement.executeUpdate("""
                create table if not exists admin_audit(id text primary key,
                    actor_user_id text references users(id),action text not null,target_type text not null,
                    target_id text,result text not null,reason_code text,correlation_id text not null,
                    created_at text not null)
                """);
        }
    }

    /**
     * Carries the v1 single-channel tables (channel-less blocks, single-row state) into the
     * channel schema under the default "network" channel, preserving the applied version.
     */
    private static void migrateFromLegacySchema(Statement statement) throws SQLException {
        boolean legacyBlocks = tableExists(statement, "exercise_bank_blocks")
            && !columnExists(statement, "exercise_bank_blocks", "channel");
        if (legacyBlocks) {
            statement.executeUpdate("alter table exercise_bank_blocks rename to exercise_bank_blocks_v1");
        }
        boolean legacyState = tableExists(statement, "exercise_bank_state")
            && !columnExists(statement, "exercise_bank_state", "channel");
        int legacyVersion = 0;
        if (legacyState) {
            try (ResultSet row = statement.executeQuery(
                "select bank_version from exercise_bank_state where id = 1")) {
                legacyVersion = row.next() ? row.getInt(1) : 0;
            }
            statement.executeUpdate("alter table exercise_bank_state rename to exercise_bank_state_v1");
        }
        if (legacyBlocks) {
            statement.executeUpdate("""
                insert into exercise_bank_blocks(channel, block_type, block_id, version, content, sha256, bank_version)
                select 'network', block_type, block_id, version, content, sha256, bank_version
                from exercise_bank_blocks_v1
                """);
            statement.executeUpdate("drop table exercise_bank_blocks_v1");
        }
        if (legacyState) {
            statement.executeUpdate(
                "insert into exercise_bank_state(channel, bank_version, updated_at)"
                    + " values ('network', " + legacyVersion + ", '" + Instant.now() + "')"
            );
            statement.executeUpdate("drop table exercise_bank_state_v1");
        }
    }

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "select 1 from sqlite_master where type = 'table' and name = '" + table + "'"
        )) {
            return rows.next();
        }
    }

    private static boolean columnExists(Statement statement, String table, String column) throws SQLException {
        try (ResultSet rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Content hashing shared by publish; isolated for clarity. */
    private static final class ExerciseBankHashing {
        private ExerciseBankHashing() {
        }

        static String sha256Hex(String content) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                return java.util.HexFormat.of()
                    .formatHex(digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }
    }
}
