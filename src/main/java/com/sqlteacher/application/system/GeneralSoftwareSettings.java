package com.sqlteacher.application.system;

public record GeneralSoftwareSettings(int formatVersion, boolean automaticUpdateChecks, String skippedVersion,
                                      ProxyMode proxyMode, String proxyHost, int proxyPort,
                                      boolean reducedMotion, boolean highContrast, boolean supportLogging,
                                      long supportLoggingExpiresAt) {
    public enum ProxyMode { DIRECT, SYSTEM, MANUAL }
    public GeneralSoftwareSettings {
        if (formatVersion != 1) throw new IllegalArgumentException("unsupported settings format");
        skippedVersion = skippedVersion == null ? "" : skippedVersion.strip();
        proxyMode = proxyMode == null ? ProxyMode.SYSTEM : proxyMode;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (proxyPort < 0 || proxyPort > 65535) throw new IllegalArgumentException("proxy port is invalid");
        if (proxyMode == ProxyMode.MANUAL && (proxyHost.isBlank() || proxyPort == 0)) {
            throw new IllegalArgumentException("manual proxy requires host and port");
        }
    }
    public static GeneralSoftwareSettings defaults() {
        return new GeneralSoftwareSettings(1, true, "", ProxyMode.SYSTEM, "", 0, false, false, false, 0);
    }
}
