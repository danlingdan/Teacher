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
        ),
        new Migration(
            2,
            "Create database connection profile tables",
            List.of(
                """
                    create table connection_profiles (
                        id text primary key,
                        display_name text not null,
                        dialect text not null check (dialect in ('SQLITE', 'MYSQL', 'MARIADB')),
                        sqlite_path text,
                        host text,
                        port integer,
                        database_name text,
                        username text,
                        read_only integer not null check (read_only in (0, 1)),
                        enabled integer not null check (enabled in (0, 1)),
                        built_in integer not null check (built_in in (0, 1)),
                        created_at text not null default current_timestamp,
                        updated_at text not null default current_timestamp,
                        check (
                            (dialect = 'SQLITE' and sqlite_path is not null
                                and host is null and port is null and database_name is null and username is null)
                            or
                            (dialect in ('MYSQL', 'MARIADB') and sqlite_path is null
                                and host is not null and port between 1 and 65535
                                and database_name is not null and username is not null)
                        )
                    )
                    """,
                """
                    create table connection_selection (
                        singleton_id integer primary key check (singleton_id = 1),
                        connection_id text not null,
                        updated_at text not null default current_timestamp
                    )
                """
            )
        ),
        new Migration(
            3,
            "Create exercise catalog and attempt tables",
            List.of(
                """
                    create table exercise_datasets (
                        id text primary key,
                        name text not null,
                        setup_sql text not null,
                        version integer not null check (version > 0),
                        created_at text not null default current_timestamp,
                        updated_at text not null default current_timestamp
                    )
                    """,
                """
                    create table exercises (
                        id text primary key,
                        title text not null,
                        description text not null,
                        knowledge_point text not null,
                        difficulty text not null check (
                            difficulty in ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')
                        ),
                        dataset_id text not null references exercise_datasets(id),
                        reference_sql text not null,
                        evaluation_rule_json text not null,
                        hints_json text not null,
                        version integer not null check (version > 0),
                        enabled integer not null check (enabled in (0, 1)),
                        created_at text not null default current_timestamp,
                        updated_at text not null default current_timestamp
                    )
                    """,
                """
                    create index exercises_enabled_order
                    on exercises(enabled, difficulty, knowledge_point, title)
                    """,
                """
                    create table exercise_sessions (
                        id text primary key,
                        exercise_id text not null references exercises(id),
                        exercise_version integer not null check (exercise_version > 0),
                        started_at text not null,
                        completed_at text,
                        hints_used integer not null default 0 check (hints_used between 0 and 3)
                    )
                    """,
                """
                    create index exercise_sessions_exercise_started
                    on exercise_sessions(exercise_id, started_at)
                    """,
                """
                    create table exercise_attempts (
                        id text primary key,
                        session_id text not null references exercise_sessions(id),
                        status text not null check (
                            status in ('RUN', 'SUBMITTED', 'PASSED', 'FAILED')
                        ),
                        sql_text text not null,
                        execution_success integer check (execution_success in (0, 1)),
                        passed integer check (passed in (0, 1)),
                        duration_ms integer not null check (duration_ms >= 0),
                        result_columns_json text not null default '[]',
                        result_rows_json text not null default '[]',
                        feedback_json text not null default '[]',
                        error_code text,
                        created_at text not null
                    )
                    """,
                """
                    create index exercise_attempts_session_created
                    on exercise_attempts(session_id, created_at)
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
