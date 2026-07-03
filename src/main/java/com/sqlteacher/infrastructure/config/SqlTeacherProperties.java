package com.sqlteacher.infrastructure.config;

import java.nio.file.Path;

public record SqlTeacherProperties(
    String appName,
    Path dataDirectory,
    DatabaseProperties database,
    AiModelProperties ai
) {
}
