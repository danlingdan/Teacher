package com.sqlteacher.infrastructure.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.update.UpdateManifest;
import com.sqlteacher.application.update.SemanticVersion;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SecureUpdateServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void acceptsOnlyPayloadsSignedByATrustedEd25519Key() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Properties keys = new Properties();
        keys.setProperty("test-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        byte[] payload = payload();
        byte[] envelope = envelope("test-key", payload, sign(pair.getPrivate(), payload));
        assertEquals("1.10.0", SecureUpdateService.verifyAndParse(envelope, keys).version().toString());

        payload[payload.length - 2] ^= 1;
        assertThrows(IllegalArgumentException.class,
            () -> SecureUpdateService.verifyAndParse(envelope("test-key", payload, sign(pair.getPrivate(), payload())), keys));
        assertThrows(IllegalArgumentException.class,
            () -> SecureUpdateService.verifyAndParse(envelope("unknown", payload(), sign(pair.getPrivate(), payload())), keys));
    }

    @Test void requiresRolloutFieldAndHonorsItsPolicy() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Properties keys = new Properties();
        keys.setProperty("test-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));

        // CMP-1.8: 缺 rollout 字段的清单必须被拒收，不得回退为"完全可见"。
        Map<String, Object> noRollout = payloadMap();
        noRollout.remove("rollout");
        byte[] missing = JSON.writeValueAsBytes(noRollout);
        assertThrows(IllegalArgumentException.class,
            () -> SecureUpdateService.verifyAndParse(envelope("test-key", missing, sign(pair.getPrivate(), missing)), keys));

        Map<String, Object> value = payloadMap();
        value.put("rollout", Map.of("percentage", 20, "paused", false));
        byte[] envelope = envelope("test-key", JSON.writeValueAsBytes(value), sign(pair.getPrivate(), JSON.writeValueAsBytes(value)));
        UpdateManifest rolledOut = SecureUpdateService.verifyAndParse(envelope, keys);
        assertEquals(20, rolledOut.rollout().percentage());
        assertTrue(rolledOut.rolloutRestrictsVisibility());
    }

    @Test void acceptsMirrorHostsAsDownloadSources() {
        java.net.URI primary = java.net.URI.create("https://github.com/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0.exe");
        var mirrors = SecureUpdateService.mirrorSources(primary);
        assertEquals(2, mirrors.size());
        assertEquals("mirror.sqlteacher.tech", mirrors.get(0).getHost());
        assertEquals("download.sqlteacher.tech", mirrors.get(1).getHost());
        assertEquals("/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0.exe", mirrors.get(0).getPath());
    }

    @Test void followsGitHubReleasesRedirectToItsCurrentAssetCdn() {
        // GitHub 将 Releases 资产 302 到 release-assets.githubusercontent.com；
        // 重定向轮的主机校验必须放行，否则官方安装包下载必然失败。
        SecureUpdateService.requireAllowed(java.net.URI.create(
            "https://release-assets.githubusercontent.com/github-production-release-asset/1287965038/asset"),
            SecureUpdateService.ALLOWED_HOSTS);
        SecureUpdateService.requireAllowed(java.net.URI.create(
            "https://github.com/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0.exe"),
            SecureUpdateService.ALLOWED_HOSTS);
        assertThrows(IllegalArgumentException.class, () -> SecureUpdateService.requireAllowed(
            java.net.URI.create("http://release-assets.githubusercontent.com/asset"), SecureUpdateService.ALLOWED_HOSTS));
        assertThrows(IllegalArgumentException.class, () -> SecureUpdateService.requireAllowed(
            java.net.URI.create("https://evil.example.com/installer.exe"), SecureUpdateService.ALLOWED_HOSTS));
    }

    @Test void acceptsManifestUrlsOnGitHubAssetCdnAndRejectsStrangerHosts() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Properties keys = new Properties();
        keys.setProperty("test-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));

        Map<String, Object> cdn = payloadMap();
        cdn.put("installerUrl", "https://release-assets.githubusercontent.com/github-production-release-asset/1287965038/SQLTeacher-1.10.0.exe");
        byte[] cdnPayload = JSON.writeValueAsBytes(cdn);
        assertEquals("1.10.0", SecureUpdateService.verifyAndParse(
            envelope("test-key", cdnPayload, sign(pair.getPrivate(), cdnPayload)), keys).version().toString());

        Map<String, Object> stranger = payloadMap();
        stranger.put("installerUrl", "https://evil.example.com/SQLTeacher-1.10.0.exe");
        byte[] strangerPayload = JSON.writeValueAsBytes(stranger);
        assertThrows(IllegalArgumentException.class,
            () -> SecureUpdateService.verifyAndParse(envelope("test-key", strangerPayload, sign(pair.getPrivate(), strangerPayload)), keys));
    }

    @Test void resumesPartialDownloadsOnlyOn206AndRestartsOtherwise() {
        assertEquals(SecureUpdateService.ResumeMode.APPEND, SecureUpdateService.resumeMode(100, 206));
        assertEquals(SecureUpdateService.ResumeMode.RESTART, SecureUpdateService.resumeMode(100, 200));
        assertEquals(SecureUpdateService.ResumeMode.RESTART, SecureUpdateService.resumeMode(0, 206));
        assertEquals(SecureUpdateService.ResumeMode.RESTART, SecureUpdateService.resumeMode(0, 200));
    }

    @Test void verifiesPrereleaseManifestsButKeepsStableInstallationsOnTheStableChannel() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Properties keys = new Properties();
        keys.setProperty("test-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        Map<String, Object> beta = payloadMap();
        beta.put("channel", "beta");
        beta.put("version", "2.0.0-beta.1");
        byte[] payload = JSON.writeValueAsBytes(beta);

        UpdateManifest manifest = SecureUpdateService.verifyAndParse(
            envelope("test-key", payload, sign(pair.getPrivate(), payload)), keys);

        assertEquals("beta", manifest.channel());
        assertFalse(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("1.11.1"), "beta"));
        assertTrue(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0-beta.1"), "beta"));
        assertTrue(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0-beta.1"), "stable"));
        assertFalse(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0-alpha.7"), "beta"));
        assertTrue(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0-rc.1"), "rc"));
        assertTrue(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0-rc.1"), "stable"));
        assertFalse(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0-rc.1"), "beta"));
        assertTrue(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0"), "stable"));
        assertFalse(SecureUpdateService.channelAllowedForBuild(SemanticVersion.parse("2.0.0"), "rc"));
    }

    private static byte[] payload() throws Exception {
        return JSON.writeValueAsBytes(payloadMap());
    }

    private static Map<String, Object> payloadMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1); value.put("product", "SQLTeacher"); value.put("channel", "stable"); value.put("version", "1.10.0");
        value.put("platform", "windows"); value.put("architecture", "x64"); value.put("publishedAt", "2026-08-02T00:00:00Z");
        value.put("releaseNotesUrl", "https://github.com/danlingdan/Teacher/releases/tag/v1.10.0");
        value.put("installerUrl", "https://github.com/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0.exe");
        value.put("installerSize", 10); value.put("installerSha256", "a".repeat(64));
        value.put("portableUrl", "https://github.com/danlingdan/Teacher/releases/download/v1.10.0/SQLTeacher-1.10.0-windows-x64.zip");
        value.put("portableSize", 10); value.put("portableSha256", "b".repeat(64)); value.put("minimumSupportedVersion", "1.9.0");
        value.put("rollout", Map.of("percentage", 100, "paused", false));
        return value;
    }
    private static byte[] sign(java.security.PrivateKey key, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key); signature.update(payload); return signature.sign();
    }
    private static byte[] envelope(String keyId, byte[] payload, byte[] signature) throws Exception {
        return JSON.writeValueAsBytes(Map.of("keyId", keyId, "payload", Base64.getEncoder().encodeToString(payload),
            "signature", Base64.getEncoder().encodeToString(signature)));
    }
}
