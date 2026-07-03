package com.sqlteacher.infrastructure.environment;

public record RuntimeEnvironment(String javaVersion, String osName, String osVersion, String architecture) {
    public static RuntimeEnvironment detect() {
        return new RuntimeEnvironment(
            System.getProperty("java.version"),
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch")
        );
    }

    public VerificationItem javaVersionItem() {
        return VerificationItem.passed("Java runtime", javaVersion);
    }

    public VerificationItem osItem() {
        return VerificationItem.passed("Operating system", osName + " " + osVersion + " (" + architecture + ")");
    }
}
