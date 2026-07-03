package com.sqlteacher.infrastructure.config;

import java.nio.file.Path;

public record DatabaseProperties(
    Path appDatabasePath,
    Path demoDatabasePath
) {
}
