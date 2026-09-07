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

/**
 * Server-side store for the distributed exercise bank. Content arrives as a validated
 * text-DSL package, is split into per-block fragments, and is published as a new
 * monotonically increasing bank version. Reads (manifest and blocks) are public; publishing
 * requires the administrator role.
 */
final class V31ExerciseBankStore {
    private static final int EXPECTED_SCHEMA = 1;
    private final Path database;
    private final ExerciseBankContent content = new ExerciseBankContent();

    V31ExerciseBankStore(Path database) throws SQLException {
        this.database = Objects.requireNonNull(database, "database must not be null").toAbsolutePath().normalize();
        initialize();
    }

    synchronized int publish(AuthenticatedUser actor, String packageText) {
        requireAdmin(actor);
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
                int bankVersion = currentVersion(connection) + 1;
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("delete from exercise_bank_blocks");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into exercise_bank_blocks(block_type, block_id, version, content, sha256, bank_version)"
                        + " values (?, ?, ?, ?, ?, ?)"
                )) {
                    for (var dataset : bank.datasets()) {
                        bindBlock(statement, "DATASET", dataset.id(), dataset.version(),
                            content.encodeDatasetBlock(dataset), bankVersion);
                        statement.addBatch();
                    }
                    for (var exercise : bank.exercises()) {
                        bindBlock(statement, "EXERCISE", exercise.id(), exercise.version(),
                            content.encodeExerciseBlock(exercise), bankVersion);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "update exercise_bank_state set bank_version = ?, updated_at = ? where id = 1"
                )) {
                    statement.setInt(1, bankVersion);
                    statement.setString(2, Instant.now().toString());
                    statement.executeUpdate();
                }
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

    Map<String, Object> manifest() {
        try (Connection connection = open()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bankVersion", currentVersion(connection));
            result.put("datasets", blockRefs(connection, "DATASET"));
            result.put("exercises", blockRefs(connection, "EXERCISE"));
            return result;
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_READ_FAILED", "Failed to read the exercise bank.", error);
        }
    }

    Map<String, Object> block(String type, String id) {
        if (!"DATASET".equals(type) && !"EXERCISE".equals(type)) {
            throw new SqlTeacherException("EXERCISE_BANK_INVALID", "Unknown exercise bank block type.");
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                 "select block_id, version, sha256, content from exercise_bank_blocks"
                     + " where block_type = ? and block_id = ?"
             )) {
            statement.setString(1, type);
            statement.setString(2, id);
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
        PreparedStatement statement, String type, String id, int version, String fragment, int bankVersion
    ) throws SQLException {
        statement.setString(1, type);
        statement.setString(2, id);
        statement.setInt(3, version);
        statement.setString(4, fragment);
        statement.setString(5, ExerciseBankHashing.sha256Hex(fragment));
        statement.setInt(6, bankVersion);
    }

    private List<Map<String, Object>> blockRefs(Connection connection, String type) throws SQLException {
        List<Map<String, Object>> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "select block_id, version, sha256 from exercise_bank_blocks"
                + " where block_type = ? order by block_id"
        )) {
            statement.setString(1, type);
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

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("select bank_version from exercise_bank_state where id = 1")) {
            return row.next() ? row.getInt(1) : 0;
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
            statement.executeUpdate("pragma user_version = " + EXPECTED_SCHEMA);
            statement.executeUpdate("""
                create table if not exists exercise_bank_blocks (
                    block_type text not null,
                    block_id text not null,
                    version integer not null,
                    content text not null,
                    sha256 text not null,
                    bank_version integer not null,
                    primary key(block_type, block_id)
                )
                """);
            statement.executeUpdate("""
                create table if not exists exercise_bank_state (
                    id integer primary key check (id = 1),
                    bank_version integer not null,
                    updated_at text not null
                )
                """);
            statement.executeUpdate(
                "insert or ignore into exercise_bank_state(id, bank_version, updated_at) values (1, 0, '1970-01-01T00:00:00Z')"
            );
        }
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
