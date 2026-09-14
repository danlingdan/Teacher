package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Shared SQLite fixtures for infrastructure tests: temporary databases migrated to the current
 * schema, so each test only seeds the data specific to its scenario. Schema-behavior tests that
 * hand-build tables before migrating (upgrade, legacy rejection) keep their own setup.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /** Migrates one database file to the current schema and returns its path. */
    public static Path migratedDatabase(Path databasePath) throws SQLException {
        new SqliteSchemaMigrator().migrate(databasePath);
        return databasePath;
    }

    /** Migrates {@code directory/app.db} to the current schema and returns its path. */
    public static Path migratedAppDatabase(Path directory) throws SQLException {
        return migratedDatabase(directory.resolve("app.db"));
    }

    /** Standard app/demo pair inside {@code directory}, without schema work. */
    public static DatabaseConfiguration configuration(Path directory) {
        return new DatabaseConfiguration(directory.resolve("app.db"), directory.resolve("demo.db"));
    }

    /**
     * Connection factory over {@code directory/app.db} + {@code directory/demo.db}
     * with the app database migrated to the current schema.
     */
    public static JdbcConnectionFactory migratedFactory(Path directory) throws SQLException {
        DatabaseConfiguration configuration = configuration(directory);
        migratedDatabase(configuration.appDatabasePath());
        return new JdbcConnectionFactory(configuration);
    }

    /** Connection factory over the given app/demo pair with the app database migrated. */
    public static JdbcConnectionFactory migratedFactory(Path appDatabase, Path demoDatabase)
            throws SQLException {
        migratedDatabase(appDatabase);
        return new JdbcConnectionFactory(new DatabaseConfiguration(appDatabase, demoDatabase));
    }
}
