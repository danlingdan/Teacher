package com.sqlteacher.server;

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
import java.util.UUID;

/**
 * Shared SQLite plumbing for the cloud API stores (v3.4.0 REF-2): one connection opener, the
 * unified schema initialization, audit writing, and token hashing. Every concrete store points
 * at the same database file, so the initialization is idempotent and safe to run from each
 * store constructor, matching the previous single {@code CloudStore} behavior.
 */
abstract class CloudStoreBase {
    final Path database;

    CloudStoreBase(Path database) throws SQLException, IOException {
        this.database = database;
        Files.createDirectories(database.getParent());
        com.sqlteacher.infrastructure.database.SqliteDriver.ensureLoaded();
        initialize();
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

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("create table if not exists users(id text primary key,email text not null unique,"
                + "display_name text not null,password_hash blob not null,password_salt blob not null,"
                + "disabled integer not null default 0,created_at text not null)");
            statement.executeUpdate("create table if not exists user_roles(user_id text not null references users(id),"
                + "role text not null check(role in ('ADMIN','TEACHER','STUDENT')),primary key(user_id,role))");
            statement.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,"
                + "user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
            statement.executeUpdate("create table if not exists refresh_tokens(token_hash blob primary key,"
                + "user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
            statement.executeUpdate("create table if not exists classrooms(id text primary key,name text not null,created_at text not null)");
            statement.executeUpdate("create table if not exists classroom_members(classroom_id text not null references classrooms(id),"
                + "user_id text not null references users(id),role text not null check(role in ('TEACHER','STUDENT')),"
                + "primary key(classroom_id,user_id))");
            statement.executeUpdate("create table if not exists class_assignments(id text primary key,"
                + "classroom_id text not null references classrooms(id),exercise_id text not null,title text not null,"
                + "description text not null default '',created_at text not null,status text not null default 'PUBLISHED',"
                + "due_at text,published_at text,copied_from_assignment_id text,version integer not null default 1,"
                + "updated_at text not null)");
            statement.executeUpdate("create table if not exists sync_events(version integer primary key autoincrement,"
                + "user_id text not null references users(id),event_id text not null,event_type text not null,"
                + "payload_json text not null,occurred_at text not null,unique(user_id,event_id))");
            statement.executeUpdate("create table if not exists assignment_submissions(id text primary key,"
                + "operation_id text not null,classroom_id text not null references classrooms(id),"
                + "assignment_id text not null references class_assignments(id),user_id text not null references users(id),"
                + "attempt_number integer not null,status text not null check(status in ('PASSED','FAILED')),"
                + "result_hash text not null,error_code text,client_completed_at text,submitted_at text not null,"
                + "unique(user_id,operation_id),unique(assignment_id,user_id,attempt_number))");
            statement.executeUpdate("create table if not exists admin_audit(id text primary key,"
                + "actor_user_id text references users(id),action text not null,target_type text not null,"
                + "target_id text,result text not null,reason_code text,correlation_id text not null,"
                + "created_at text not null)");
            statement.executeUpdate("create table if not exists export_audit(id text primary key,"
                + "user_id text not null references users(id),classroom_id text not null references classrooms(id),"
                + "row_count integer not null,created_at text not null,assignment_id text,"
                + "export_type text not null default 'CLASS_ANALYTICS',filter_summary text)");
            statement.executeUpdate("create table if not exists retention_jobs(id text primary key,"
                + "category text not null,cutoff text not null,preview_count integer not null,"
                + "affected_count integer not null default 0,status text not null,confirmation_hash blob not null,"
                + "expires_at text not null,backup_reference text,safety_backup text,created_at text not null,executed_at text,"
                + "restored_at text,actor_user_id text not null references users(id))");
            statement.executeUpdate("create table if not exists retention_archive(job_id text not null "
                + "references retention_jobs(id),category text not null,row_key text not null,payload_json text not null,"
                + "archived_at text not null,primary key(job_id,row_key))");
            addColumnIfMissing(statement, "class_assignments", "status text not null default 'PUBLISHED'");
            addColumnIfMissing(statement, "class_assignments", "due_at text");
            addColumnIfMissing(statement, "class_assignments", "updated_at text");
            addColumnIfMissing(statement, "class_assignments", "description text not null default ''");
            addColumnIfMissing(statement, "class_assignments", "published_at text");
            addColumnIfMissing(statement, "class_assignments", "copied_from_assignment_id text");
            addColumnIfMissing(statement, "class_assignments", "version integer not null default 1");
            addColumnIfMissing(statement, "export_audit", "assignment_id text");
            addColumnIfMissing(statement, "export_audit", "export_type text not null default 'CLASS_ANALYTICS'");
            addColumnIfMissing(statement, "export_audit", "filter_summary text");
            addColumnIfMissing(statement, "retention_jobs", "safety_backup text");
            statement.executeUpdate("update class_assignments set updated_at=created_at where updated_at is null");
            statement.executeUpdate("update class_assignments set description='' where description is null");
            statement.executeUpdate("update class_assignments set version=1 where version is null or version<1");
            statement.executeUpdate("update class_assignments set published_at=created_at "
                + "where published_at is null and status<>'DRAFT'");
            statement.executeUpdate("create index if not exists idx_assignments_class_status "
                + "on class_assignments(classroom_id,status,created_at desc)");
            statement.executeUpdate("create index if not exists idx_submissions_assignment_user "
                + "on assignment_submissions(assignment_id,user_id,submitted_at)");
            statement.executeUpdate("create index if not exists idx_admin_audit_action_time "
                + "on admin_audit(action,created_at desc)");
            statement.executeUpdate("create index if not exists idx_retention_jobs_created "
                + "on retention_jobs(created_at desc)");
        }
    }

    private void addColumnIfMissing(Statement statement, String table, String definition) throws SQLException {
        String column = definition.split("\\s+", 2)[0];
        boolean exists = false;
        try (ResultSet row = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (row.next()) {
                if (column.equalsIgnoreCase(row.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) statement.executeUpdate("alter table " + table + " add column " + definition);
    }
}
