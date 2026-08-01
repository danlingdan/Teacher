package com.sqlteacher.application.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Determines whether an installation may see a stable update during a controlled
 * rollout. Buckets are anonymous: the hash input is only the installation id, the
 * target version and the platform; no hardware, network address or learning
 * behaviour is used, and the same inputs always produce the same bucket.
 */
public final class RolloutDecider {
    private RolloutDecider() { }

    /**
     * @param installId persistent anonymous installation identifier
     * @param version   target version string (buckets change per version)
     * @param platform  target platform (windows/mac/linux)
     */
    public static boolean visible(String installId, String version, String platform, Rollout rollout) {
        if (rollout == null) return true;
        if (rollout.paused() || rollout.percentage() <= 0) return false;
        if (rollout.percentage() >= 100) return true;
        int bucket = bucket(installId, version, platform);
        return bucket < rollout.percentage();
    }

    static int bucket(String installId, String version, String platform) {
        String input = String.join("|", installId == null ? "" : installId, version == null ? "" : version,
            platform == null ? "" : platform);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(bytes, 0, 8);
            long value = Math.floorMod(Long.parseUnsignedLong(hex, 16), 100L);
            return (int) value;
        } catch (Exception error) {
            throw new IllegalStateException("unable to compute rollout bucket", error);
        }
    }
}
