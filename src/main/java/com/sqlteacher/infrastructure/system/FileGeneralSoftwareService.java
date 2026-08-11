package com.sqlteacher.infrastructure.system;

import com.sqlteacher.application.system.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FileGeneralSoftwareService implements GeneralSoftwareService {
    private final Path dataDirectory;
    private final Path supportDirectory;
    private final Path settingsFile;
    private final URI cloudBaseUri;
    private final List<TaskSnapshot> tasks = new CopyOnWriteArrayList<>();
    private final List<AppNotification> notifications = new CopyOnWriteArrayList<>();

    public FileGeneralSoftwareService(Path dataDirectory, URI cloudBaseUri) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.supportDirectory = this.dataDirectory.resolve("support");
        this.settingsFile = supportDirectory.resolve("general-settings.json");
        this.cloudBaseUri = cloudBaseUri;
    }

    @Override public GeneralSoftwareSettings settings() {
        GeneralSoftwareSettings value = AtomicJsonFile.read(settingsFile, GeneralSoftwareSettings.class, GeneralSoftwareSettings.defaults());
        if (value.supportLogging() && value.supportLoggingExpiresAt() < System.currentTimeMillis()) {
            value = new GeneralSoftwareSettings(1, value.automaticUpdateChecks(), value.skippedVersion(), value.proxyMode(),
                value.proxyHost(), value.proxyPort(), value.reducedMotion(), value.highContrast(), false, 0,
                value.updateMirrorsEnabled(), value.language(), value.nativeNotificationsEnabled(), value.meteredNetwork(),
                value.theme(), value.font(), value.density());
            saveSettings(value);
        }
        return value;
    }

    @Override public void saveSettings(GeneralSoftwareSettings settings) { AtomicJsonFile.write(settingsFile, settings); }

    @Override public Path exportSettings(Path destination) {
        Path target = destination.toAbsolutePath().normalize();
        AtomicJsonFile.write(target, settings());
        return target;
    }

    @Override public GeneralSoftwareSettings importSettings(Path source) {
        if (!Files.isRegularFile(source) || size(source) > 64 * 1024) throw new IllegalArgumentException("settings file is invalid");
        GeneralSoftwareSettings imported = AtomicJsonFile.read(source, GeneralSoftwareSettings.class, null);
        if (imported == null) throw new IllegalArgumentException("settings file is invalid");
        saveSettings(imported);
        return imported;
    }

    @Override public StorageOverview storage() {
        Map<String, Long> categories = new LinkedHashMap<>();
        categories.put("database", matchingSize(dataDirectory, name -> name.endsWith(".db")));
        categories.put("knowledge", treeSize(dataDirectory.resolve("knowledge")));
        categories.put("cache", treeSize(dataDirectory.resolve("cache")) + treeSize(dataDirectory.resolve("knowledge-index")));
        categories.put("logs", treeSize(logDirectory()));
        categories.put("backups", treeSize(dataDirectory.resolve("backups")));
        categories.put("updates", treeSize(dataDirectory.resolve("updates")));
        categories.put("diagnostics", treeSize(supportDirectory.resolve("diagnostics")));
        categories.put("support-drafts", treeSize(supportDirectory.resolve("drafts")));
        try { return new StorageOverview(categories, Files.getFileStore(dataDirectory).getUsableSpace()); }
        catch (IOException error) { return new StorageOverview(categories, -1); }
    }

    @Override public long clearRebuildableFiles() {
        long[] removed = {0};
        removeTree(dataDirectory.resolve("updates"), removed, path -> true);
        removeTree(supportDirectory.resolve("diagnostics"), removed,
            path -> modifiedBefore(path, Instant.now().minus(30, ChronoUnit.DAYS)));
        removeTree(dataDirectory.resolve("cache"), removed, path -> true);
        notify(AppNotification.Category.TASK, "空间清理完成", "已清理 " + formatBytes(removed[0]) + " 可重建文件。", "settings");
        return removed[0];
    }

    @Override public List<TaskSnapshot> tasks() { return tasks.stream().sorted(Comparator.comparing(TaskSnapshot::startedAt).reversed()).limit(50).toList(); }
    @Override public String startTask(String type, String title, boolean cancellable) {
        String id = UUID.randomUUID().toString();
        tasks.add(new TaskSnapshot(id, safe(type), safe(title), TaskSnapshot.Status.RUNNING, 0, cancellable, false, "", Instant.now(), null));
        return id;
    }
    @Override public void updateTask(String id, double progress) { replaceTask(id, old -> copy(old, TaskSnapshot.Status.RUNNING, Math.clamp(progress, 0, 1), "", false, null)); }
    @Override public void completeTask(String id) { replaceTask(id, old -> copy(old, TaskSnapshot.Status.SUCCEEDED, 1, "", false, Instant.now())); }
    @Override public void failTask(String id, String errorCode, boolean retryable) { replaceTask(id, old -> copy(old, TaskSnapshot.Status.FAILED, old.progress(), safe(errorCode), retryable, Instant.now())); }

    @Override public List<AppNotification> notifications() { return notifications.stream().sorted(Comparator.comparing(AppNotification::createdAt).reversed()).limit(100).toList(); }
    @Override public void notify(AppNotification.Category category, String title, String message, String target) {
        String key = category + ":" + safe(title) + ":" + safe(target);
        notifications.removeIf(item -> (item.category() + ":" + item.title() + ":" + item.target()).equals(key));
        notifications.add(new AppNotification(UUID.randomUUID().toString(), category, safe(title), safe(message), safe(target), false, Instant.now()));
        while (notifications.size() > 100) notifications.removeFirst();
    }
    @Override public void markNotificationsRead() {
        for (int index = 0; index < notifications.size(); index++) {
            AppNotification item = notifications.get(index);
            notifications.set(index, new AppNotification(item.id(), item.category(), item.title(), item.message(), item.target(), true, item.createdAt()));
        }
    }

    @Override public String connectivitySummary() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest request = HttpRequest.newBuilder(cloudBaseUri.resolve("/health")).timeout(Duration.ofSeconds(6)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? "Cloud HTTPS 正常" : "Cloud 返回 HTTP " + status;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return "连接检查已取消"; }
        catch (IOException | RuntimeException error) { return "Cloud 不可用：" + error.getClass().getSimpleName(); }
    }

    @Override public List<String> helpTopics() { return List.of("getting-started", "updates", "feedback", "privacy", "shortcuts", "troubleshooting"); }
    @Override public String help(String topicId) {
        return switch (topicId) {
            case "getting-started" -> "SQLTeacher 的本地数据默认保存在用户数据目录。AI 输出始终是草稿，执行前仍需 SQL 安全检查。";
            case "updates" -> "在设置的“更新与支持”中手动检查。只有签名清单、大小和 SHA-256 全部通过后才能启动安装器。";
            case "feedback" -> "反馈发送前可预览诊断字段。数据库、SQL、Prompt、密码、Token 和 AI Key 不会默认上传。";
            case "privacy" -> "SQLTeacher 默认不收集使用遥测。问题反馈和诊断包仅在用户明确操作后生成或发送。";
            case "shortcuts" -> "Ctrl+1 首页，Ctrl+2 我的练习，Ctrl+3 课程知识，Ctrl+, 设置，F1 帮助。";
            case "troubleshooting" -> "先检查数据目录空间、Cloud HTTPS 和 Ollama 状态；仍失败时导出诊断包并附上错误码。";
            default -> throw new IllegalArgumentException("help topic does not exist");
        };
    }

    private void replaceTask(String id, java.util.function.UnaryOperator<TaskSnapshot> change) {
        for (int index = 0; index < tasks.size(); index++) if (tasks.get(index).id().equals(id)) { tasks.set(index, change.apply(tasks.get(index))); return; }
    }
    private static TaskSnapshot copy(TaskSnapshot old, TaskSnapshot.Status status, double progress, String code, boolean retryable, Instant finished) {
        return new TaskSnapshot(old.id(), old.type(), old.title(), status, progress, old.cancellable(), retryable, code, old.startedAt(), finished);
    }
    private Path logDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.isBlank() ? Path.of("app-data", "SQLTeacher", "logs") : Path.of(local, "SQLTeacher", "logs");
    }
    private long matchingSize(Path root, java.util.function.Predicate<String> predicate) {
        if (!Files.isDirectory(root)) return 0;
        try (var entries = Files.list(root)) { return entries.filter(Files::isRegularFile).filter(p -> predicate.test(p.getFileName().toString())).mapToLong(FileGeneralSoftwareService::size).sum(); }
        catch (IOException error) { return 0; }
    }
    private long treeSize(Path root) {
        if (!Files.exists(root) || Files.isSymbolicLink(root)) return 0;
        long[] total = {0};
        try { Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) { if (!attrs.isSymbolicLink()) total[0] += attrs.size(); return FileVisitResult.CONTINUE; }
        }); } catch (IOException ignored) { }
        return total[0];
    }
    private void removeTree(Path root, long[] removed, java.util.function.Predicate<Path> predicate) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataDirectory) || normalized.equals(dataDirectory) || !Files.exists(normalized) || Files.isSymbolicLink(normalized)) return;
        try { Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isSymbolicLink() && predicate.test(file)) { removed[0] += attrs.size(); Files.deleteIfExists(file); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error == null && !dir.equals(normalized)) try (var entries = Files.list(dir)) { if (entries.findAny().isEmpty()) Files.deleteIfExists(dir); }
                return FileVisitResult.CONTINUE;
            }
        }); } catch (IOException error) { throw new IllegalStateException("Unable to clean rebuildable files", error); }
    }
    private static boolean modifiedBefore(Path path, Instant cutoff) { try { return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff); } catch (IOException error) { return false; } }
    private static long size(Path path) { try { return Files.size(path); } catch (IOException error) { return 0; } }
    private static String safe(String value) { return SensitiveDataRedactor.redact(value == null ? "" : value).strip(); }
    private static String formatBytes(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)); }
}
