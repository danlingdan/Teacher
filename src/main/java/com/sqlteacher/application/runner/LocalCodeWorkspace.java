package com.sqlteacher.application.runner;

import java.nio.file.Path;
import java.util.Objects;

public record LocalCodeWorkspace(Path directory, String environmentName) {
    public LocalCodeWorkspace {
        directory = Objects.requireNonNull(directory, "directory must not be null").toAbsolutePath().normalize();
        environmentName = Objects.requireNonNull(environmentName, "environmentName must not be null").trim();
        if (environmentName.isEmpty()) throw new IllegalArgumentException("environmentName must not be blank");
    }
}
