package com.sqlteacher.application.config;

import java.nio.file.Path;
import java.util.Objects;

public record SqlTeacherConfiguration(
    String appName,
    Path dataDirectory,
    DatabaseConfiguration database,
    AiConfiguration ai
) {
    public SqlTeacherConfiguration {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must not be blank");
        }
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(ai, "ai must not be null");
    }
}
