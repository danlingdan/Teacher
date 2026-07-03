package com.sqlteacher.application.database;

import java.nio.file.Path;

public record DatabaseInitializationResult(
    Path appDatabasePath,
    Path demoDatabasePath,
    boolean appDatabaseCreated,
    boolean demoDatabaseCreated
) {
}
