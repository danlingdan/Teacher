package com.sqlteacher.infrastructure.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.system.AppNotification;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sqlteacher.application.update.*;
import com.sqlteacher.infrastructure.system.AtomicJsonFile;
import com.sqlteacher.infrastructure.system.ConfiguredHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.DoubleConsumer;

public final class SecureUpdateService implements UpdateService {
    private static final Set<String> ALLOWED_HOSTS = Set.of("api.sqlteacher.tech", "github.com", "objects.githubusercontent.com");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final URI endpoint;
    private final Path updateDirectory;
    private final Path stateFile;
    private final GeneralSoftwareService system;
    private final HttpClient client;
    private final Properties keys = new Properties();

    public SecureUpdateService(URI cloudBaseUri, Path dataDirectory, GeneralSoftwareService system) {
        endpoint = cloudBaseUri.resolve("/api/v1/app/update-manifest");
        updateDirectory = dataDirectory.toAbsolutePath().normalize().resolve("updates");
        stateFile = dataDirectory.toAbsolutePath().normalize().resolve("support/update-state.json");
        this.system = system;
        client = ConfiguredHttpClient.create(system, HttpClient.Redirect.NEVER);
        try (InputStream stream = SecureUpdateService.class.getResourceAsStream("/update-public-keys.properties")) {
            if (stream != null) keys.load(stream);
        } catch (IOException error) { throw new IllegalStateException("Unable to load update public keys", error); }
    }

    @Override public UpdateCheckResult check(boolean manual) {
        ApplicationBuildInfo current = ApplicationBuildInfo.current();
        UpdateState state = AtomicJsonFile.read(stateFile, UpdateState.class, new UpdateState(null, ""));
        if (!manual && state.lastCheckedAt() != null && state.lastCheckedAt().isAfter(Instant.now().minus(Duration.ofHours(24)))) {
            return new UpdateCheckResult(UpdateCheckResult.Status.UP_TO_DATE, current, null, "自动检查已在 24 小时内完成");
        }
        String task = system.startTask("UPDATE_CHECK", "检查软件更新", false);
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12)).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 128 * 1024) throw new IllegalStateException("更新服务暂时不可用");
            UpdateManifest manifest = verifyAndParse(response.body(), keys);
            AtomicJsonFile.write(stateFile, new UpdateState(Instant.now(), manifest.version().toString()));
            system.completeTask(task);
            if (!manifest.platform().equalsIgnoreCase(current.platform()) || !architectureMatches(manifest.architecture(), current.architecture())) {
                return new UpdateCheckResult(UpdateCheckResult.Status.FAILED, current, null, "没有适用于当前平台的更新包");
            }
            if (current.version().compareTo(manifest.minimumSupportedVersion()) < 0) {
                return new UpdateCheckResult(UpdateCheckResult.Status.UNSUPPORTED, current, manifest, "当前版本低于云端最低兼容版本，请尽快更新");
            }
            if (manifest.version().compareTo(current.version()) <= 0) {
                return new UpdateCheckResult(UpdateCheckResult.Status.UP_TO_DATE, current, null, "当前已是最新稳定版");
            }
            if (manifest.version().toString().equals(system.settings().skippedVersion()) && !manual) {
                return new UpdateCheckResult(UpdateCheckResult.Status.SKIPPED, current, manifest, "此版本已跳过");
            }
            system.notify(AppNotification.Category.UPDATE, "发现 SQLTeacher " + manifest.version(), "更新已准备好，可查看说明后下载。", "updates");
            return new UpdateCheckResult(UpdateCheckResult.Status.AVAILABLE, current, manifest, "发现新版本 " + manifest.version());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); system.failTask(task, "UPDATE_CHECK_CANCELLED", true);
            return new UpdateCheckResult(UpdateCheckResult.Status.FAILED, current, null, "更新检查已取消");
        } catch (RuntimeException | IOException error) {
            system.failTask(task, "UPDATE_CHECK_FAILED", true);
            return new UpdateCheckResult(UpdateCheckResult.Status.FAILED, current, null, "更新检查失败，不影响离线功能");
        }
    }

    @Override public Path download(UpdateManifest manifest, DoubleConsumer progress) {
        String task = system.startTask("UPDATE_DOWNLOAD", "下载 SQLTeacher " + manifest.version(), true);
        Path temporary = updateDirectory.resolve("SQLTeacher-" + manifest.version() + ".exe.part");
        try {
            Files.createDirectories(updateDirectory);
            if (Files.getFileStore(updateDirectory).getUsableSpace() < manifest.installerSize() + 128L * 1024 * 1024) {
                throw new IllegalStateException("磁盘空间不足，无法安全下载更新");
            }
            Path ready = updateDirectory.resolve("SQLTeacher-" + manifest.version() + ".exe");
            Files.deleteIfExists(temporary);
            URI uri = manifest.installerUrl();
            HttpResponse<InputStream> response = null;
            for (int redirects = 0; redirects <= 3; redirects++) {
                requireAllowed(uri);
                response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() / 100 != 3) break;
                String location = response.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("更新下载重定向无效"));
                uri = uri.resolve(location);
            }
            if (response == null || response.statusCode() != 200) throw new IllegalStateException("更新下载失败");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > manifest.installerSize()) throw new IllegalStateException("更新文件超过签名清单大小");
                    digest.update(buffer, 0, read); output.write(buffer, 0, read);
                    double value = Math.min(1, total / (double) manifest.installerSize()); progress.accept(value); system.updateTask(task, value);
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (total != manifest.installerSize() || !hash.equalsIgnoreCase(manifest.installerSha256())) {
                Files.deleteIfExists(temporary); throw new IllegalStateException("更新文件完整性校验失败");
            }
            moveReady(temporary, ready);
            system.completeTask(task); return ready;
        } catch (InterruptedException error) {
            deleteQuietly(temporary);
            Thread.currentThread().interrupt(); system.failTask(task, "UPDATE_DOWNLOAD_CANCELLED", true); throw new IllegalStateException("更新下载已取消", error);
        } catch (Exception error) {
            deleteQuietly(temporary);
            system.failTask(task, "UPDATE_DOWNLOAD_FAILED", true); throw new IllegalStateException(error.getMessage() == null ? "更新下载失败" : error.getMessage(), error);
        }
    }

    @Override public boolean ready(UpdateManifest manifest, Path installer) {
        try { return Files.isRegularFile(installer) && Files.size(installer) == manifest.installerSize()
            && sha256(installer).equalsIgnoreCase(manifest.installerSha256()); }
        catch (Exception error) { return false; }
    }

    @Override public void launchInstaller(UpdateManifest manifest, Path installer) {
        if (!ready(manifest, installer)) throw new IllegalStateException("安装器尚未通过完整性校验");
        try { new ProcessBuilder(installer.toAbsolutePath().toString()).directory(installer.getParent().toFile()).start(); }
        catch (IOException error) { throw new IllegalStateException("无法启动安装器", error); }
    }

    @Override public void skip(SemanticVersion version) {
        GeneralSoftwareSettings old = system.settings();
        system.saveSettings(new GeneralSoftwareSettings(1, old.automaticUpdateChecks(), version.toString(), old.proxyMode(),
            old.proxyHost(), old.proxyPort(), old.reducedMotion(), old.highContrast(), old.supportLogging(), old.supportLoggingExpiresAt()));
    }
    @Override public void clearDownloadedUpdates() { system.clearRebuildableFiles(); }

    static UpdateManifest verifyAndParse(byte[] envelopeBytes, Properties trustedKeys) {
        try {
            JsonNode envelope = JSON.readTree(envelopeBytes);
            String keyId = text(envelope, "keyId", 64);
            byte[] payload = Base64.getDecoder().decode(text(envelope, "payload", 120_000));
            byte[] signatureBytes = Base64.getDecoder().decode(text(envelope, "signature", 256));
            String publicKey = trustedKeys.getProperty(keyId);
            if (publicKey == null) throw new IllegalArgumentException("unknown update signing key");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
            verifier.update(payload);
            if (!verifier.verify(signatureBytes)) throw new IllegalArgumentException("update signature is invalid");
            JsonNode node = JSON.readTree(payload);
            return new UpdateManifest(node.path("schemaVersion").asInt(), text(node, "product", 40), text(node, "channel", 20),
                SemanticVersion.parse(text(node, "version", 128)), text(node, "platform", 30), text(node, "architecture", 30),
                Instant.parse(text(node, "publishedAt", 64)), URI.create(text(node, "releaseNotesUrl", 2048)),
                URI.create(text(node, "installerUrl", 2048)), node.path("installerSize").asLong(), text(node, "installerSha256", 64),
                URI.create(text(node, "portableUrl", 2048)), node.path("portableSize").asLong(), text(node, "portableSha256", 64),
                SemanticVersion.parse(text(node, "minimumSupportedVersion", 128)));
        } catch (Exception error) { throw new IllegalArgumentException("update manifest is not trusted", error); }
    }
    private static String text(JsonNode node, String field, int max) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("update field is invalid: " + field);
        return value;
    }
    private static void requireAllowed(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("update download host is not allowed");
        }
    }
    private static boolean architectureMatches(String manifest, String runtime) {
        String value = runtime.toLowerCase(Locale.ROOT);
        return manifest.equalsIgnoreCase("x64") && (value.equals("amd64") || value.equals("x86_64"));
    }
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void moveReady(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void deleteQuietly(Path file) { try { Files.deleteIfExists(file); } catch (IOException ignored) { } }
    private record UpdateState(Instant lastCheckedAt, String lastSeenVersion) { }
}
