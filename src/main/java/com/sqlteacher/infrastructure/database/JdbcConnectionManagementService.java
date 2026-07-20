package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTarget;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.domain.SqlTeacherException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcConnectionManagementService implements ConnectionManagementService {
    private static final String APP_CONNECTION_ID = "app";
    private static final String DEMO_CONNECTION_ID = "demo";

    private final JdbcConnectionFactory connectionFactory;
    private final DatabaseConfiguration configuration;

    public JdbcConnectionManagementService(
        JdbcConnectionFactory connectionFactory,
        DatabaseConfiguration configuration
    ) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    public List<DatabaseConnectionProfile> listProfiles() {
        return execute(connection -> {
            List<DatabaseConnectionProfile> profiles = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                select id, display_name, dialect, sqlite_path, host, port, database_name, username,
                       read_only, enabled, built_in
                from connection_profiles
                order by built_in desc,
                         case id when 'demo' then 0 when 'app' then 1 else 2 end,
                         display_name collate nocase,
                         id
                """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    profiles.add(mapProfile(resultSet));
                }
            }
            return List.copyOf(profiles);
        });
    }

    @Override
    public Optional<DatabaseConnectionProfile> findProfile(String connectionId) {
        String normalizedId = requireConnectionId(connectionId);
        return execute(connection -> findProfile(connection, normalizedId));
    }

    @Override
    public DatabaseConnectionProfile saveProfile(DatabaseConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (profile.builtIn() || isReservedId(profile.id())) {
            throw new IllegalArgumentException("Built-in connection profiles cannot be replaced");
        }

        return execute(connection -> {
            upsertUserProfile(connection, profile);
            repairSelection(connection);
            return findProfile(connection, profile.id()).orElseThrow();
        });
    }

    @Override
    public void removeProfile(String connectionId) {
        String normalizedId = requireConnectionId(connectionId);
        if (isReservedId(normalizedId)) {
            throw new IllegalArgumentException("Built-in connection profiles cannot be removed");
        }

        execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from connection_profiles where id = ? and built_in = 0"
            )) {
                statement.setString(1, normalizedId);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Connection profile does not exist: " + normalizedId);
                }
            }
            repairSelection(connection);
            return null;
        });
    }

    @Override
    public Optional<DatabaseConnectionProfile> currentProfile() {
        return execute(connection -> {
            repairSelection(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                select p.id, p.display_name, p.dialect, p.sqlite_path, p.host, p.port,
                       p.database_name, p.username, p.read_only, p.enabled, p.built_in
                from connection_selection s
                join connection_profiles p on p.id = s.connection_id
                where s.singleton_id = 1 and p.enabled = 1
                """);
                 ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProfile(resultSet)) : Optional.empty();
            }
        });
    }

    @Override
    public DatabaseConnectionProfile selectProfile(String connectionId) {
        String normalizedId = requireConnectionId(connectionId);
        return execute(connection -> {
            DatabaseConnectionProfile profile = findProfile(connection, normalizedId)
                .orElseThrow(() -> new IllegalArgumentException(
                    "Connection profile does not exist: " + normalizedId
                ));
            if (!profile.enabled()) {
                throw new IllegalArgumentException("Disabled connection profile cannot be selected");
            }
            select(connection, normalizedId);
            return profile;
        });
    }

    private <T> T execute(SqlOperation<T> operation) {
        try (Connection connection = connectionFactory.open(APP_CONNECTION_ID)) {
            connection.setAutoCommit(false);
            try {
                ensureBuiltInProfiles(connection);
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException(
                "CONNECTION_PROFILE_FAILED",
                "Failed to manage database connection profiles",
                error
            );
        }
    }

    private void ensureBuiltInProfiles(Connection connection) throws SQLException {
        upsertBuiltInProfile(connection, new DatabaseConnectionProfile(
            APP_CONNECTION_ID,
            "应用数据库",
            new SqliteConnectionTarget(configuration.appDatabasePath()),
            true,
            true,
            true
        ));
        upsertBuiltInProfile(connection, new DatabaseConnectionProfile(
            DEMO_CONNECTION_ID,
            "SQLite 演示数据库",
            new SqliteConnectionTarget(configuration.demoDatabasePath()),
            false,
            true,
            true
        ));
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into connection_selection(singleton_id, connection_id)
            values (1, ?)
            on conflict(singleton_id) do nothing
            """)) {
            statement.setString(1, DEMO_CONNECTION_ID);
            statement.executeUpdate();
        }
        repairSelection(connection);
    }

    private static void upsertBuiltInProfile(
        Connection connection,
        DatabaseConnectionProfile profile
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into connection_profiles(
                id, display_name, dialect, sqlite_path, host, port, database_name, username,
                read_only, enabled, built_in
            ) values (?, ?, ?, ?, null, null, null, null, ?, 1, 1)
            on conflict(id) do update set
                display_name = excluded.display_name,
                dialect = excluded.dialect,
                sqlite_path = excluded.sqlite_path,
                host = null,
                port = null,
                database_name = null,
                username = null,
                read_only = excluded.read_only,
                enabled = 1,
                built_in = 1,
                updated_at = current_timestamp
            """)) {
            SqliteConnectionTarget target = (SqliteConnectionTarget) profile.target();
            statement.setString(1, profile.id());
            statement.setString(2, profile.displayName());
            statement.setString(3, profile.dialect().name());
            statement.setString(4, target.databasePath().toString());
            statement.setInt(5, profile.readOnly() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void upsertUserProfile(
        Connection connection,
        DatabaseConnectionProfile profile
    ) throws SQLException {
        DatabaseConnectionTarget target = profile.target();
        String sqlitePath = null;
        String host = null;
        Integer port = null;
        String databaseName = null;
        String username = null;
        if (target instanceof SqliteConnectionTarget sqliteTarget) {
            sqlitePath = sqliteTarget.databasePath().toString();
        } else if (target instanceof ServerConnectionTarget serverTarget) {
            host = serverTarget.host();
            port = serverTarget.port();
            databaseName = serverTarget.databaseName();
            username = serverTarget.username();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            insert into connection_profiles(
                id, display_name, dialect, sqlite_path, host, port, database_name, username,
                read_only, enabled, built_in
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            on conflict(id) do update set
                display_name = excluded.display_name,
                dialect = excluded.dialect,
                sqlite_path = excluded.sqlite_path,
                host = excluded.host,
                port = excluded.port,
                database_name = excluded.database_name,
                username = excluded.username,
                read_only = excluded.read_only,
                enabled = excluded.enabled,
                updated_at = current_timestamp
            where connection_profiles.built_in = 0
            """)) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.displayName());
            statement.setString(3, profile.dialect().name());
            statement.setString(4, sqlitePath);
            statement.setString(5, host);
            if (port == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, port);
            }
            statement.setString(7, databaseName);
            statement.setString(8, username);
            statement.setInt(9, profile.readOnly() ? 1 : 0);
            statement.setInt(10, profile.enabled() ? 1 : 0);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Built-in connection profiles cannot be replaced");
            }
        }
    }

    private static Optional<DatabaseConnectionProfile> findProfile(
        Connection connection,
        String connectionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select id, display_name, dialect, sqlite_path, host, port, database_name, username,
                   read_only, enabled, built_in
            from connection_profiles
            where id = ?
            """)) {
            statement.setString(1, connectionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapProfile(resultSet)) : Optional.empty();
            }
        }
    }

    private static DatabaseConnectionProfile mapProfile(ResultSet resultSet) throws SQLException {
        DatabaseDialect dialect = DatabaseDialect.valueOf(resultSet.getString("dialect"));
        DatabaseConnectionTarget target = dialect == DatabaseDialect.SQLITE
            ? new SqliteConnectionTarget(Path.of(resultSet.getString("sqlite_path")))
            : new ServerConnectionTarget(
                dialect,
                resultSet.getString("host"),
                resultSet.getInt("port"),
                resultSet.getString("database_name"),
                resultSet.getString("username")
            );
        return new DatabaseConnectionProfile(
            resultSet.getString("id"),
            resultSet.getString("display_name"),
            target,
            resultSet.getInt("read_only") == 1,
            resultSet.getInt("enabled") == 1,
            resultSet.getInt("built_in") == 1
        );
    }

    private static void repairSelection(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            update connection_selection
            set connection_id = ?, updated_at = current_timestamp
            where singleton_id = 1
              and not exists (
                  select 1 from connection_profiles p
                  where p.id = connection_selection.connection_id and p.enabled = 1
              )
            """)) {
            statement.setString(1, DEMO_CONNECTION_ID);
            statement.executeUpdate();
        }
    }

    private static void select(Connection connection, String connectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            update connection_selection
            set connection_id = ?, updated_at = current_timestamp
            where singleton_id = 1
            """)) {
            statement.setString(1, connectionId);
            statement.executeUpdate();
        }
    }

    private static String requireConnectionId(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        String normalized = connectionId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        return normalized;
    }

    private static boolean isReservedId(String connectionId) {
        return APP_CONNECTION_ID.equals(connectionId) || DEMO_CONNECTION_ID.equals(connectionId);
    }

    private static void rollback(Connection connection, Throwable originalError) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            originalError.addSuppressed(rollbackError);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
