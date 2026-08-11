package com.sqlteacher.application.system;

public record GeneralSoftwareSettings(int formatVersion, boolean automaticUpdateChecks, String skippedVersion,
                                      ProxyMode proxyMode, String proxyHost, int proxyPort,
                                      boolean reducedMotion, boolean highContrast, boolean supportLogging,
                                      long supportLoggingExpiresAt, boolean updateMirrorsEnabled, String language,
                                      boolean nativeNotificationsEnabled, boolean meteredNetwork,
                                      String theme, String font, String density) {
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
        language = language == null || language.isBlank() ? "zh" : language.strip().toLowerCase(java.util.Locale.ROOT);
        if (!"zh".equals(language) && !"en".equals(language)) throw new IllegalArgumentException("language must be zh or en");
        theme = normalized(theme, "system", java.util.Set.of("system", "light", "dark"), "theme");
        font = normalized(font, "modern", java.util.Set.of("modern", "system", "classic"), "font");
        density = normalized(density, "comfortable", java.util.Set.of("comfortable", "compact"), "density");
    }
    public static GeneralSoftwareSettings defaults() {
        return new GeneralSoftwareSettings(1, true, "", ProxyMode.SYSTEM, "", 0, false, false, false, 0, false,
            "zh", false, false, "system", "modern", "comfortable");
    }
    private static String normalized(String value, String fallback, java.util.Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }
}
