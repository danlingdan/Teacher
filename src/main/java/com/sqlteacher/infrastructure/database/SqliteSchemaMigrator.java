package com.sqlteacher.infrastructure.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class SqliteSchemaMigrator {
    private static final List<Migration> DEFAULT_MIGRATIONS = List.of(
        new Migration(
            1,
            "Create the Demo baseline application tables",
            List.of(
                """
                    create table if not exists app_event (
                        id integer primary key autoincrement,
                        event_type text not null,
                        message text,
                        created_at text not null default current_timestamp
                    )
                    """,
                """
                    create table if not exists learning_events (
                        id integer primary key autoincrement,
                        event_type text not null,
                        occurred_at text not null,
                        connection_id text not null,
                        successful integer not null,
                        attributes text,
                        created_at text not null default current_timestamp
                    )
                    """
            )
        )
    );

    private final List<Migration> migrations;

    SqliteSchemaMigrator() {
        this(DEFAULT_MIGRATIONS);
    }

    SqliteSchemaMigrator(List<Migration> migrations) {
        this.migrations = validateMigrations(migrations);
    }

    int migrate(Path databasePath) throws SQLException {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.setAutoCommit(false);
            try {
                createVersionTable(connection);
                List<Integer> appliedVersions = readAppliedVersions(connection);
                validateAppliedVersions(appliedVersions);

                for (int index = appliedVersions.size(); index < migrations.size(); index++) {
                    applyMigration(connection, migrations.get(index));
                }

                connection.commit();
                return latestVersion();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    int latestVersion() {
        return migrations.getLast().version();
    }

    private static void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                create table if not exists schema_version (
                    version integer primary key,
                    description text not null,
                    applied_at text not null default current_timestamp
                )
                """);
        }
    }

    private static List<Integer> readAppliedVersions(Connection connection) throws SQLException {
        List<Integer> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select version from schema_version order by version")) {
            while (resultSet.next()) {
                versions.add(resultSet.getInt("version"));
            }
        }
        return List.copyOf(versions);
    }

    private void validateAppliedVersions(List<Integer> appliedVersions) throws SQLException {
        if (appliedVersions.size() > migrations.size()) {
            throw new SQLException("Application database schema is newer than this SQLTeacher version");
        }
        for (int index = 0; index < appliedVersions.size(); index++) {
            int expected = migrations.get(index).version();
            int actual = appliedVersions.get(index);
            if (actual != expected) {
                throw new SQLException(
                    "Application database migration history is invalid: expected version "
                        + expected + " but found " + actual
                );
            }
        }
    }

    private static void applyMigration(Connection connection, Migration migration) throws SQLException {
        for (String sql : migration.statements()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into schema_version(version, description) values (?, ?)"
        )) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Throwable originalError) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            originalError.addSuppressed(rollbackError);
        }
    }

    private static List<Migration> validateMigrations(List<Migration> migrations) {
        if (migrations == null || migrations.isEmpty()) {
            throw new IllegalArgumentException("At least one SQLite schema migration is required");
        }
        List<Migration> copy = List.copyOf(migrations);
        for (int index = 0; index < copy.size(); index++) {
            int expectedVersion = index + 1;
            if (copy.get(index).version() != expectedVersion) {
                throw new IllegalArgumentException(
                    "SQLite schema migrations must be ordered and contiguous from version 1"
                );
            }
        }
        return copy;
    }

    record Migration(int version, String description, List<String> statements) {
        Migration {
            if (version < 1) {
                throw new IllegalArgumentException("Migration version must be positive");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Migration description must not be blank");
            }
            if (statements == null || statements.isEmpty()) {
                throw new IllegalArgumentException("Migration statements must not be empty");
            }
            statements = List.copyOf(statements);
            if (statements.stream().anyMatch(sql -> sql == null || sql.isBlank())) {
                throw new IllegalArgumentException("Migration SQL must not be blank");
            }
        }
    }
}
