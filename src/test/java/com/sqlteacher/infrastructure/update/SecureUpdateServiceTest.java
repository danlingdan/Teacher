package com.sqlteacher.infrastructure.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.update.UpdateManifest;
import com.sqlteacher.application.update.SemanticVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Test void createsThePartFileWhenDownloadingIntoAFreshUpdatesDirectory(@TempDir Path dir) throws Exception {
        // 回归：写盘输出流此前缺少 CREATE 选项，首次下载（.part 尚不存在）必然抛
        // NoSuchFileException，弹窗里只剩一条裸路径——应用内更新从未真正成功过。
        byte[] body = "SQLTeacherInstallerBytes".getBytes(StandardCharsets.UTF_8);
        Path part = dir.resolve("SQLTeacher-3.5.4.exe.part");
        List<Double> progress = new ArrayList<>();

        SecureUpdateService.pumpToPart(new ByteArrayInputStream(body), part,
            SecureUpdateService.ResumeMode.RESTART, 0, body.length, progress::add, System::nanoTime);

        assertArrayEquals(body, Files.readAllBytes(part));
        assertEquals(List.of(1.0), progress);
        assertFalse(Files.exists(dir.resolve("SQLTeacher-3.5.4.exe")), "下载阶段不得产生就绪文件");
    }

    @Test void appendResumesAndRestartTruncatesAnExistingPartFile(@TempDir Path dir) throws Exception {
        byte[] head = "HEAD".getBytes(StandardCharsets.UTF_8);
        byte[] tail = "TAIL".getBytes(StandardCharsets.UTF_8);
        Path part = dir.resolve("SQLTeacher-3.5.4.exe.part");
        Files.write(part, head);

        SecureUpdateService.pumpToPart(new ByteArrayInputStream(tail), part,
            SecureUpdateService.ResumeMode.APPEND, head.length, head.length + tail.length, value -> { }, System::nanoTime);
        assertArrayEquals("HEADTAIL".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(part));

        Files.write(part, "STALE-CONTENT-FROM-AN-OLDER-ATTEMPT".getBytes(StandardCharsets.UTF_8));
        SecureUpdateService.pumpToPart(new ByteArrayInputStream(tail), part,
            SecureUpdateService.ResumeMode.RESTART, 0, tail.length, value -> { }, System::nanoTime);
        assertArrayEquals(tail, Files.readAllBytes(part));
    }

    @Test void rejectsBodiesThatMissOrExceedTheSignedInstallerSize(@TempDir Path dir) throws Exception {
        Path part = dir.resolve("SQLTeacher-3.5.4.exe.part");

        RuntimeException oversized = assertThrows(RuntimeException.class, () ->
            SecureUpdateService.pumpToPart(new ByteArrayInputStream(new byte[30]), part,
                SecureUpdateService.ResumeMode.RESTART, 0, 20, value -> { }, System::nanoTime));
        assertTrue(oversized.getMessage().contains("超过签名清单大小"));

        RuntimeException shortRead = assertThrows(RuntimeException.class, () ->
            SecureUpdateService.pumpToPart(new ByteArrayInputStream(new byte[5]), part,
                SecureUpdateService.ResumeMode.RESTART, 0, 20, value -> { }, System::nanoTime));
        assertTrue(shortRead.getMessage().contains("大小不匹配"));
    }

    @Test void abandonsASourceThatStopsDeliveringBytes(@TempDir Path dir) throws Exception {
        // 时钟由读取次数驱动：第二次 read 返回时模拟已停滞 2 个超时周期，
        // 随后循环顶部的停滞检查必须放弃该源，并保留已写入字节供断点续传。
        long[] fakeNanos = {0};
        InputStream stalling = new InputStream() {
            int reads;
            @Override public int read() { fakeNanos[0] += SecureUpdateService.STALL_TIMEOUT_NANOS; return -1; }
            @Override public int read(byte[] buffer, int off, int len) {
                reads++;
                fakeNanos[0] += reads == 1 ? 1 : SecureUpdateService.STALL_TIMEOUT_NANOS * 2;
                return 3;
            }
        };
        Path part = dir.resolve("SQLTeacher-3.5.4.exe.part");

        IOException stalled = assertThrows(IOException.class, () ->
            SecureUpdateService.pumpToPart(stalling, part, SecureUpdateService.ResumeMode.RESTART,
                0, 20, value -> { }, () -> fakeNanos[0]));
        assertTrue(stalled.getMessage().contains("停滞"), stalled.getMessage());
        assertEquals(3, Files.size(part), "停滞前已写入的字节必须保留供断点续传");
    }

    @Test void relaysGithubReleaseUrlsOntoTheCloudHostAndSkipsStrangers() {
        URI primary = URI.create("https://github.com/danlingdan/Teacher/releases/download/v3.5.3/SQLTeacher-3.5.3.exe");
        assertEquals(URI.create("https://api.sqlteacher.tech/gh/danlingdan/Teacher/releases/download/v3.5.3/SQLTeacher-3.5.3.exe"),
            SecureUpdateService.relaySource(primary, "api.sqlteacher.tech"));

        assertEquals(URI.create("https://api.sqlteacher.tech/gh/danlingdan/Teacher/releases/download/v3.5.3/x.exe?t=1"),
            SecureUpdateService.relaySource(URI.create("https://github.com/danlingdan/Teacher/releases/download/v3.5.3/x.exe?t=1"),
                "api.sqlteacher.tech"));

        // 非 GitHub 源或未配置中继主机时不得改写；非 HTTPS 一律拒绝改写。
        assertNull(SecureUpdateService.relaySource(primary, ""));
        assertNull(SecureUpdateService.relaySource(
            URI.create("https://release-assets.githubusercontent.com/asset"), "api.sqlteacher.tech"));
        assertNull(SecureUpdateService.relaySource(
            URI.create("http://github.com/danlingdan/Teacher/releases/download/v3.5.3/SQLTeacher-3.5.3.exe"),
            "api.sqlteacher.tech"));
    }

    @Test void launchInstallerRefusesAFileThatFailsVerification(@TempDir Path dir) throws Exception {
        // 安装器文件缺失或哈希不匹配时必须先拒绝启动，而不是把未校验的 exe 交给系统。
        GeneralSoftwareService system = (GeneralSoftwareService) java.lang.reflect.Proxy.newProxyInstance(
            GeneralSoftwareService.class.getClassLoader(), new Class<?>[]{GeneralSoftwareService.class},
            (instance, method, args) -> {
                if ("settings".equals(method.getName())) {
                    return com.sqlteacher.application.system.GeneralSoftwareSettings.defaults();
                }
                throw new UnsupportedOperationException(method.getName() + " is not stubbed");
            });
        SecureUpdateService service = new SecureUpdateService(
            java.net.URI.create("https://api.sqlteacher.tech"), dir, system, java.util.Set.of());
        var manifest = new com.sqlteacher.application.update.UpdateManifest(1, "SQLTeacher", "stable",
            SemanticVersion.parse("3.5.3"), "windows", "x64", java.time.Instant.parse("2026-09-16T21:22:11Z"),
            java.net.URI.create("https://github.com/danlingdan/Teacher/releases/tag/v3.5.3"),
            java.net.URI.create("https://github.com/danlingdan/Teacher/releases/download/v3.5.3/SQLTeacher-3.5.3.exe"),
            10, "a".repeat(64),
            java.net.URI.create("https://github.com/danlingdan/Teacher/releases/download/v3.5.3/SQLTeacher-3.5.3-windows-x64.zip"),
            10, "b".repeat(64),
            SemanticVersion.parse("1.9.0"), new com.sqlteacher.application.update.Rollout(100, false));

        var missing = assertThrows(com.sqlteacher.domain.SqlTeacherException.class,
            () -> service.launchInstaller(manifest, dir.resolve("SQLTeacher-3.5.3.exe")));
        assertEquals("UPDATE_INSTALL_NOT_READY", missing.errorCode());

        java.nio.file.Files.write(dir.resolve("SQLTeacher-3.5.3.exe"), new byte[9]);
        var wrongSize = assertThrows(com.sqlteacher.domain.SqlTeacherException.class,
            () -> service.launchInstaller(manifest, dir.resolve("SQLTeacher-3.5.3.exe")));
        assertEquals("UPDATE_INSTALL_NOT_READY", wrongSize.errorCode());
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
