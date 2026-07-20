package com.sqlteacher.application.connection;

public sealed interface DatabaseConnectionTarget
    permits SqliteConnectionTarget, ServerConnectionTarget {

    DatabaseDialect dialect();
}
