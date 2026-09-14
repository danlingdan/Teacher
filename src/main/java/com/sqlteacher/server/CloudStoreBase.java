package com.sqlteacher.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared SQLite plumbing for the cloud API stores (v3.4.0 REF-2): one connection opener, audit
 * writing, and token hashing. Since v3.4.0 REF-5 the schema itself is owned by the shared
 * {@link CloudSchemaMigrator}, which every store constructor triggers before its first
 * {@link #open()}.
 */
abstract class CloudStoreBase {
    final Path database;

    CloudStoreBase(Path database) throws SQLException, IOException {
        this.database = database;
        Files.createDirectories(database.getParent());
        com.sqlteacher.infrastructure.database.SqliteDriver.ensureLoaded();
        CloudSchemaMigrator.migrate(database);
    }

    Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("pragma busy_timeout=5000");
        }
        return connection;
    }

    void audit(Connection connection, String actorUserId, String action, String targetType,
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

    byte[] tokenHash(String token) {
        return Hashes.sha256Bytes(token);
    }

    static IllegalStateException database(SQLException e) {
        return new IllegalStateException("Cloud database operation failed", e);
    }
}
