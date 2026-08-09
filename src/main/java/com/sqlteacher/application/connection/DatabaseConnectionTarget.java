package com.sqlteacher.application.connection;

public sealed interface DatabaseConnectionTarget
    permits SqliteConnectionTarget, FileDatabaseConnectionTarget, ServerConnectionTarget,
        GenericJdbcConnectionTarget {

    DatabaseDialect dialect();
}
