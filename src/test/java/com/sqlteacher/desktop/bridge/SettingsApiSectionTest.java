package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferencesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithoutCore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the settings section keeps local maintenance behind the admin permission guard,
 * normalizes bank subscription channels before persisting them, and enforces the check-then-
 * download-then-install update order entirely in Java without a Spring core.
 */
class SettingsApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void settingsHelpRejectsBlankTopicIdsWithoutTheCore() {
        SettingsApiSection section = new SettingsApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("settings.help", mapper.createObjectNode()
                .put("topicId", "  "), () -> false, ignored -> { }));
        assertEquals("topicId must contain at most 128 characters", error.getMessage());
    }

    @Test
    void settingsHelpReturnsTheTopicContent() throws Exception {
        List<String> topics = new ArrayList<>();
        GeneralSoftwareService general = fake(GeneralSoftwareService.class, Map.of(
            "help", args -> {
                topics.add((String) args[0]);
                return "备份与恢复说明";
            }));
        try (var host = hostWithBeans(general)) {
            SettingsApiSection section = new SettingsApiSection(host);

            JsonNode result = section.handle("settings.help", mapper.createObjectNode()
                .put("topicId", "backup"), () -> false, ignored -> { });

            assertEquals("backup", result.path("topicId").asText());
            assertEquals("备份与恢复说明", result.path("content").asText());
        }
        assertEquals(List.of("backup"), topics);
    }

    @Test
    void settingsBankUpdateNormalizesChannelsAndPersists() throws Exception {
        var store = new ExerciseBankPreferencesStore(tempDirectory);
        ObjectNode params = mapper.createObjectNode().put("autoCheckEnabled", false);
        params.putArray("subscribedChannels").add("  NETWORK ").add("").add("Cloud");
        try (var host = hostWithBeans(store)) {
            SettingsApiSection section = new SettingsApiSection(host);

            JsonNode saved = section.handle("settings.bank.update", params, () -> false, ignored -> { });
            assertTrue(saved.path("saved").asBoolean());
            var preferences = store.load();
            assertFalse(preferences.autoCheckEnabled());
            assertEquals(List.of("network", "cloud"), preferences.subscribedChannels());

            // 空订阅列表回落到默认频道，未提供的开关沿用已保存的值。
            ObjectNode fallbackParams = mapper.createObjectNode();
            fallbackParams.putArray("subscribedChannels");
            JsonNode fallback = section.handle("settings.bank.update", fallbackParams, () -> false, ignored -> { });
            assertTrue(fallback.path("saved").asBoolean());
            var reloaded = store.load();
            assertFalse(reloaded.autoCheckEnabled());
            assertEquals(List.of("network"), reloaded.subscribedChannels());
        }
    }

    @Test
    void settingsLearningResetRequiresTheAdminMaintenancePermission() {
        try (var host = hostWithBeans(new ApiSectionTestSupport.FakeCloudSessions())) {
            SettingsApiSection section = new SettingsApiSection(host);

            // 即使带上了精确的确认短语，访客也不允许触碰本地数据维护。
            SecurityException error = assertThrows(SecurityException.class,
                () -> section.handle("settings.learning.reset", mapper.createObjectNode()
                    .put("confirmation", "RESET LEARNING DATA"), () -> false, ignored -> { }));
            assertEquals("Local data maintenance is not allowed for the current role", error.getMessage());
        }
    }

    @Test
    void settingsUpdateFlowEnforcesCheckThenDownloadThenInstallOrder() {
        SettingsApiSection section = new SettingsApiSection(hostWithoutCore());

        IllegalArgumentException download = assertThrows(IllegalArgumentException.class,
            () -> section.handle("settings.update.download", mapper.createObjectNode(), () -> false, ignored -> { }));
        assertEquals("请先检查更新", download.getMessage());

        IllegalArgumentException install = assertThrows(IllegalArgumentException.class,
            () -> section.handle("settings.update.install", mapper.createObjectNode(), () -> false, ignored -> { }));
        assertEquals("请先下载更新", install.getMessage());
    }

    /** 与云端签发清单同构的测试清单；URL/哈希满足 UpdateManifest 的 HTTPS 与 64 位十六进制校验。 */
    private static com.sqlteacher.application.update.UpdateManifest manifest(String version) {
        return new com.sqlteacher.application.update.UpdateManifest(1, "SQLTeacher", "stable",
            com.sqlteacher.application.update.SemanticVersion.parse(version), "windows", "x64",
            java.time.Instant.parse("2026-09-16T21:22:11Z"),
            java.net.URI.create("https://github.com/danlingdan/Teacher/releases/tag/v" + version),
            java.net.URI.create("https://github.com/danlingdan/Teacher/releases/download/v" + version + "/SQLTeacher-" + version + ".exe"),
            10, "a".repeat(64),
            java.net.URI.create("https://github.com/danlingdan/Teacher/releases/download/v" + version + "/SQLTeacher-" + version + "-windows-x64.zip"),
            10, "b".repeat(64),
            com.sqlteacher.application.update.SemanticVersion.parse("1.9.0"),
            new com.sqlteacher.application.update.Rollout(100, false));
    }

    @Test
    void settingsUpdateForceDownloadStagesInstallerWithoutANewerVersion(@TempDir Path tempDirectory) throws Exception {
        // 强制下载不做版本门控：直接取最新签名清单 → 下载 → 暂存，随后无需再检查即可启动安装（重装闭环）。
        var manifest = manifest("3.5.3");
        Path staged = tempDirectory.resolve("SQLTeacher-3.5.3.exe");
        java.nio.file.Files.write(staged, new byte[10]);
        List<Object[]> launched = new ArrayList<>();
        var update = fake(com.sqlteacher.application.update.UpdateService.class, Map.of(
            "latest", args -> manifest,
            "download", args -> {
                assertSame(manifest, args[0]);
                java.util.function.DoubleConsumer progress = (java.util.function.DoubleConsumer) args[1];
                progress.accept(0.25);
                progress.accept(1.0);
                return staged;
            },
            "ready", args -> true,
            "launchInstaller", args -> {
                launched.add(args);
                return null;
            }));
        try (var host = hostWithBeans(update)) {
            SettingsApiSection section = new SettingsApiSection(host);
            List<LocalAppEvent> events = new ArrayList<>();

            JsonNode downloaded = section.handle("settings.update.forceDownload",
                mapper.createObjectNode(), () -> false, events::add);

            assertTrue(downloaded.path("ready").asBoolean());
            assertEquals("3.5.3", downloaded.path("version").asText());
            assertEquals(List.of(0.25, 1.0), events.stream()
                .map(event -> event.payload().path("fraction").asDouble())
                .toList());
            assertEquals(List.of("progress", "progress"), events.stream().map(LocalAppEvent::type).toList());

            JsonNode installed = section.handle("settings.update.install",
                mapper.createObjectNode(), () -> false, events::add);
            assertTrue(installed.path("launched").asBoolean());
            assertEquals(1, launched.size());
            assertSame(manifest, launched.get(0)[0]);
        }
    }

    @Test
    void settingsUpdateDownloadAbortsWhenCancelledMidProgress() throws Exception {
        // 取消在下一个进度帧生效；部分文件由服务层按设计保留，请求以 CANCELLED 落回前端。
        var checkResult = new com.sqlteacher.application.update.UpdateCheckResult(
            com.sqlteacher.application.update.UpdateCheckResult.Status.AVAILABLE, null, manifest("3.5.3"), "发现新版本 3.5.3");
        var update = fake(com.sqlteacher.application.update.UpdateService.class, Map.of(
            "check", args -> checkResult,
            "download", args -> {
                java.util.function.DoubleConsumer progress = (java.util.function.DoubleConsumer) args[1];
                progress.accept(0.5);
                return Path.of("unused");
            }));
        try (var host = hostWithBeans(update)) {
            SettingsApiSection section = new SettingsApiSection(host);
            section.handle("settings.update.check", mapper.createObjectNode(), () -> false, ignored -> { });

            assertThrows(LocalAppCancelledException.class, () ->
                section.handle("settings.update.download", mapper.createObjectNode(), () -> true, ignored -> { }));
        }
    }

    @Test
    void settingsComponentCancelRejectsUnknownComponentIds() {
        SettingsApiSection section = new SettingsApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("settings.component.cancel", mapper.createObjectNode()
                .put("componentId", "NOPE"), () -> false, ignored -> { }));
        assertTrue(error.getMessage().contains("ManagedComponentId.NOPE"), error.getMessage());
    }
}
