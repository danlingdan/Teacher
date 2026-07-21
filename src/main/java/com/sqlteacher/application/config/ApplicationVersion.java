package com.sqlteacher.application.config;

public final class ApplicationVersion {
    private static final String DEVELOPMENT_VERSION = "development";

    private ApplicationVersion() {
    }

    public static String current() {
        String implementationVersion = ApplicationVersion.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
            ? DEVELOPMENT_VERSION
            : implementationVersion;
    }
}
