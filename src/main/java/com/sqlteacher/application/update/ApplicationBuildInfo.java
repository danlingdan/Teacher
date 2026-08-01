package com.sqlteacher.application.update;

import com.sqlteacher.application.config.ApplicationVersion;

public record ApplicationBuildInfo(String product, SemanticVersion version, String commit, String buildTime,
                                   String installType, String platform, String architecture) {
    public static ApplicationBuildInfo current() {
        Package metadata = ApplicationBuildInfo.class.getPackage();
        String commit = clean(metadata == null ? null : metadata.getImplementationVendor(), "development");
        String buildTime = clean(metadata == null ? null : metadata.getSpecificationVersion(), "development");
        String installType = clean(System.getProperty("sqlteacher.install.type"), "development");
        String rawVersion = ApplicationVersion.current();
        SemanticVersion version = "development".equals(rawVersion) ? SemanticVersion.parse("0.0.0-development") : SemanticVersion.parse(rawVersion);
        return new ApplicationBuildInfo("SQLTeacher", version, commit,
            buildTime, installType, "windows", System.getProperty("os.arch", "unknown"));
    }

    public String supportSummary() {
        return product + " " + version + " · " + installType + " · " + platform + "/" + architecture
            + " · Java " + System.getProperty("java.version", "unknown");
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() || value.startsWith("${") ? fallback : value.strip();
    }
}
