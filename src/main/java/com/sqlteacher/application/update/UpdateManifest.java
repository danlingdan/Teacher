package com.sqlteacher.application.update;

import java.net.URI;
import java.time.Instant;

public record UpdateManifest(int schemaVersion, String product, String channel, SemanticVersion version,
                             String platform, String architecture, Instant publishedAt, URI releaseNotesUrl,
                             URI installerUrl, long installerSize, String installerSha256,
                             URI portableUrl, long portableSize, String portableSha256,
                             SemanticVersion minimumSupportedVersion, Rollout rollout) {
    public UpdateManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported update schema");
        if (!"SQLTeacher".equals(product) || !java.util.Set.of("stable", "alpha", "beta", "rc").contains(channel)) {
            throw new IllegalArgumentException("unsupported update product or channel");
        }
        if (installerSize <= 0 || portableSize <= 0 || installerSize > 512L * 1024 * 1024 || portableSize > 512L * 1024 * 1024) {
            throw new IllegalArgumentException("update artifact size is invalid");
        }
        validateHash(installerSha256); validateHash(portableSha256);
        validateHttps(releaseNotesUrl); validateHttps(installerUrl); validateHttps(portableUrl);
    }

    /** Rollout is optional; a {@code null} value means fully visible and not paused. */
    public boolean rolloutRestrictsVisibility() {
        return rollout != null && (rollout.paused() || rollout.percentage() < 100);
    }

    private static void validateHash(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("update hash is invalid");
    }
    private static void validateHttps(URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null) {
            throw new IllegalArgumentException("update URL must use HTTPS");
        }
        String host = value.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!host.equals("github.com") && !host.equals("api.sqlteacher.tech") && !host.equals("objects.githubusercontent.com")) {
            throw new IllegalArgumentException("update URL host is not allowed");
        }
    }
}
