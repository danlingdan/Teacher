package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.component.ManagedComponentId;
import com.sqlteacher.application.component.ManagedComponentService;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sqlteacher.application.update.UpdateCheckResult;
import com.sqlteacher.application.update.UpdateManifest;
import com.sqlteacher.application.update.UpdateService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferences;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferencesStore;
import com.sqlteacher.application.risk.SqlSafetyModeService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: settings workspace, preferences, maintenance, components, and in-app updates. */
final class SettingsApiSection extends ApiSection {

    /** 最近一次检查结果，供下载/安装续接使用（W7：应用内更新闭环）。 */
    private volatile UpdateCheckResult lastUpdateCheck;
    private volatile Path downloadedInstaller;
    private volatile UpdateManifest downloadedManifest;

    SettingsApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "settings.workspace", "settings.preferences", "settings.environment", "settings.storage",
            "settings.update", "settings.component.install", "settings.component.cancel",
            "settings.backups", "settings.backup.create", "settings.backup.restore", "settings.demo.restore",
            "settings.learning.reset", "settings.cache.clear", "settings.update.check",
            "settings.update.download", "settings.update.install", "settings.update.skip",
            "settings.notifications.read", "settings.help", "settings.bank.update"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "settings.workspace" -> settingsWorkspace(cancellation);
            case "settings.preferences" -> settingsPreferences(cancellation);
            case "settings.environment" -> settingsEnvironment(cancellation);
            case "settings.storage" -> settingsStorage(cancellation);
            case "settings.update" -> settingsUpdate(params, cancellation);
            case "settings.component.install" -> settingsComponentInstall(params, cancellation, events);
            case "settings.component.cancel" -> settingsComponentCancel(params);
            case "settings.backups" -> settingsBackups(cancellation);
            case "settings.backup.create" -> settingsBackupCreate(cancellation);
            case "settings.backup.restore" -> settingsBackupRestore(params, cancellation);
            case "settings.demo.restore" -> settingsDemoRestore(cancellation);
            case "settings.learning.reset" -> settingsLearningReset(params, cancellation);
            case "settings.cache.clear" -> settingsCacheClear(cancellation);
            case "settings.update.check" -> settingsUpdateCheck(cancellation);
            case "settings.update.download" -> settingsUpdateDownload(cancellation, events);
            case "settings.update.install" -> settingsUpdateInstall(cancellation);
            case "settings.update.skip" -> settingsUpdateSkip(params, cancellation);
            case "settings.notifications.read" -> settingsNotificationsRead(cancellation);
            case "settings.help" -> settingsHelp(params, cancellation);
            case "settings.bank.update" -> settingsBankUpdate(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode settingsWorkspace(CancellationToken cancellation) {
        ObjectNode result = settingsPreferences(cancellation);
        result.setAll(settingsEnvironment(cancellation));
        result.setAll(settingsStorage(cancellation));
        return result;
    }

    private ObjectNode settingsPreferences(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        var profile = currentAccessProfile();
        GeneralSoftwareService general = core.getBean(GeneralSoftwareService.class);
        GeneralSoftwareSettings settings = general.settings();
        ObjectNode result = mapper.createObjectNode();
        result.put("role", webRole(profile));
        result.put("developerMode", core.getBean(SqlSafetyModeService.class).isDeveloperModeEnabled());
        result.put("developerModeExplicit", core.getBean(SqlSafetyModeService.class).isDeveloperModeExplicit());
        result.put("canMaintainLocalData", profile.canConfigure(
            com.sqlteacher.application.collaboration.DesktopSettingPermission.LOCAL_DATA_MAINTENANCE));
        result.set("general", mapper.valueToTree(settings));
        var bankStore = core.getBean(ExerciseBankPreferencesStore.class);
        var bankPrefs = bankStore.load();
        var bankNode = mapper.createObjectNode();
        bankNode.put("autoCheckEnabled", bankPrefs.autoCheckEnabled());
        bankNode.set("subscribedChannels", mapper.valueToTree(bankPrefs.subscribedChannels()));
        result.set("bank", bankNode);
        result.set("notifications", mapper.valueToTree(general.notifications()));
        result.set("tasks", mapper.valueToTree(general.tasks()));
        result.set("helpTopics", mapper.valueToTree(general.helpTopics()));
        result.put("secretsExposed", false);
        return result;
    }

    private ObjectNode settingsEnvironment(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        ObjectNode result = mapper.createObjectNode();
        result.put("connectivity", core.getBean(GeneralSoftwareService.class).connectivitySummary());
        cancellation.throwIfCancelled();
        result.set("runnerCapabilities", mapper.valueToTree(core.getBean(LocalCodeRunner.class).capabilities()));
        cancellation.throwIfCancelled();
        result.set("components", mapper.valueToTree(core.getBean(ManagedComponentService.class).statuses()));
        result.put("manualPathPolicy", "java.home, JAVA_HOME, JDK_HOME, PATH and documented tool locations");
        return result;
    }

    private ObjectNode settingsStorage(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("storage",
            mapper.valueToTree(context().getBean(GeneralSoftwareService.class).storage()));
    }

    private JsonNode settingsUpdate(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        GeneralSoftwareService service = core.getBean(GeneralSoftwareService.class);
        GeneralSoftwareSettings current = service.settings();
        boolean developerMode = params.path("developerMode").asBoolean(
            core.getBean(SqlSafetyModeService.class).isDeveloperModeEnabled());
        core.getBean(SqlSafetyModeService.class).setDeveloperModeEnabled(developerMode);
        String language = params.path("language").asText(current.language());
        boolean supportLogging = params.path("supportLogging").asBoolean(current.supportLogging());
        long supportLoggingExpiresAt = supportLogging
            ? (current.supportLogging() && current.supportLoggingExpiresAt() > System.currentTimeMillis()
                ? current.supportLoggingExpiresAt() : System.currentTimeMillis() + java.time.Duration.ofHours(24).toMillis())
            : 0;
        GeneralSoftwareSettings updated = new GeneralSoftwareSettings(
            current.formatVersion(),
            params.path("automaticUpdateChecks").asBoolean(current.automaticUpdateChecks()),
            current.skippedVersion(), GeneralSoftwareSettings.ProxyMode.valueOf(
                params.path("proxyMode").asText(current.proxyMode().name())),
            params.path("proxyHost").asText(current.proxyHost()),
            params.path("proxyPort").asInt(current.proxyPort()),
            params.path("reducedMotion").asBoolean(current.reducedMotion()),
            params.path("highContrast").asBoolean(current.highContrast()),
            supportLogging,
            supportLoggingExpiresAt,
            params.path("updateMirrorsEnabled").asBoolean(current.updateMirrorsEnabled()),
            language,
            params.path("nativeNotificationsEnabled").asBoolean(current.nativeNotificationsEnabled()),
            params.path("meteredNetwork").asBoolean(current.meteredNetwork()),
            params.path("theme").asText(current.theme()),
            params.path("font").asText(current.font()),
            params.path("density").asText(current.density()));
        service.saveSettings(updated);
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().put("saved", true).put("developerMode", developerMode)
            .set("general", mapper.valueToTree(updated));
    }

    private JsonNode settingsComponentInstall(JsonNode params, CancellationToken cancellation,
                                              Consumer<LocalAppEvent> events) {
        ManagedComponentId id = ManagedComponentId.valueOf(requiredText(params, "componentId", 64));
        cancellation.throwIfCancelled();
        var status = context().getBean(ManagedComponentService.class).install(id, progress -> {
            cancellation.throwIfCancelled();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("componentId", id.name());
            payload.put("fraction", progress.fraction());
            payload.put("message", progress.message());
            events.accept(new LocalAppEvent("progress", payload));
        });
        cancellation.throwIfCancelled();
        return mapper.valueToTree(status);
    }

    private JsonNode settingsComponentCancel(JsonNode params) {
        ManagedComponentId id = ManagedComponentId.valueOf(requiredText(params, "componentId", 64));
        context().getBean(ManagedComponentService.class).cancel(id);
        return mapper.createObjectNode().put("cancelled", true).put("componentId", id.name());
    }

    private JsonNode settingsBackups(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items",
            mapper.valueToTree(context().getBean(ApplicationBackupService.class).listBackups()));
    }

    private JsonNode settingsBackupCreate(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ApplicationBackupService.class).createBackup());
    }

    private JsonNode settingsBackupRestore(JsonNode params, CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        String backupId = requiredText(params, "backupId", 512);
        context().getBean(ApplicationBackupService.class).restoreBackup(backupId);
        return mapper.createObjectNode().put("restored", true).put("backupId", backupId);
    }

    private JsonNode settingsDemoRestore(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        context().getBean(ApplicationBackupService.class).restoreDemoDatabase();
        return mapper.createObjectNode().put("restored", true);
    }

    private JsonNode settingsLearningReset(JsonNode params, CancellationToken cancellation) {
        requireLocalMaintenance();
        if (!"RESET LEARNING DATA".equals(params.path("confirmation").asText())) {
            throw new IllegalArgumentException("Learning data reset requires the exact confirmation phrase");
        }
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(DataMaintenanceService.class).resetLearningData());
    }

    private JsonNode settingsCacheClear(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        long bytes = context().getBean(GeneralSoftwareService.class).clearRebuildableFiles();
        return mapper.createObjectNode().put("clearedBytes", bytes);
    }

    private JsonNode settingsUpdateCheck(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var result = context().getBean(UpdateService.class).check(true);
        lastUpdateCheck = result;
        return mapper.valueToTree(result);
    }

    private JsonNode settingsUpdateDownload(CancellationToken cancellation, Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        var manifest = lastUpdateCheck == null ? null : lastUpdateCheck.available();
        if (manifest == null) throw new IllegalArgumentException("请先检查更新");
        var service = context().getBean(UpdateService.class);
        Path installer = service.download(manifest, fraction -> {
            ObjectNode progress = mapper.createObjectNode();
            progress.put("phase", "update.download");
            progress.put("fraction", fraction);
            events.accept(new LocalAppEvent("progress", progress));
        });
        if (!service.ready(manifest, installer)) {
            throw new SqlTeacherException("UPDATE_DOWNLOAD_FAILED", "下载的安装包未通过校验，请重试。");
        }
        downloadedInstaller = installer;
        downloadedManifest = manifest;
        return mapper.createObjectNode().put("ready", true);
    }

    private JsonNode settingsUpdateInstall(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var manifest = downloadedManifest;
        var installer = downloadedInstaller;
        if (manifest == null || installer == null) {
            throw new IllegalArgumentException("请先下载更新");
        }
        context().getBean(UpdateService.class).launchInstaller(manifest, installer);
        return mapper.createObjectNode().put("launched", true);
    }

    private JsonNode settingsUpdateSkip(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String version = requiredText(params, "version", 32);
        context().getBean(UpdateService.class).skip(
            com.sqlteacher.application.update.SemanticVersion.parse(version));
        return mapper.createObjectNode().put("skipped", version);
    }

    private JsonNode settingsNotificationsRead(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(GeneralSoftwareService.class).markNotificationsRead();
        return mapper.createObjectNode().put("updated", true);
    }

    private JsonNode settingsHelp(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String topicId = requiredText(params, "topicId", 128);
        return mapper.createObjectNode().put("topicId", topicId)
            .put("content", context().getBean(GeneralSoftwareService.class).help(topicId));
    }

    private JsonNode settingsBankUpdate(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var store = context().getBean(ExerciseBankPreferencesStore.class);
        var current = store.load();
        boolean autoCheck = params.path("autoCheckEnabled").asBoolean(current.autoCheckEnabled());
        List<String> requested = new ArrayList<>();
        params.withArray("subscribedChannels")
            .forEach(item -> requested.add(item.asText("").trim().toLowerCase(java.util.Locale.ROOT)));
        requested.removeIf(String::isEmpty);
        List<String> channels = requested.isEmpty()
            ? List.of(ExerciseBankPreferences.DEFAULT_CHANNEL)
            : List.copyOf(requested);
        store.save(new ExerciseBankPreferences(autoCheck, channels, current.pendingNotice()));
        return mapper.createObjectNode().put("saved", true);
    }
}
