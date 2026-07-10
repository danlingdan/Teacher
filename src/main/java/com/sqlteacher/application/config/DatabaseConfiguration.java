package com.sqlteacher.application.config;

import java.nio.file.Path;
import java.util.Objects;

public record DatabaseConfiguration(
    Path appDatabasePath,
    Path demoDatabasePath
) {
    public DatabaseConfiguration {
        Objects.requireNonNull(appDatabasePath, "appDatabasePath must not be null");
        Objects.requireNonNull(demoDatabasePath, "demoDatabasePath must not be null");
    }
}
