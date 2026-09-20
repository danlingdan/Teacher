package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.CloudSyncApi;
import com.sqlteacher.application.collaboration.CloudApiRequestException;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.CloudSyncPreferences;
import com.sqlteacher.application.event.LearningEvent;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventUploadPolicy;
import com.sqlteacher.application.event.LearningEventType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Idempotent account-scoped synchronization with persisted diagnostics and bounded retries. */
public final class DefaultCloudLearningSyncService implements CloudLearningSyncService {
    private static final String CLOUD_ID = "_cloud_event_id";
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration[] RETRY_DELAYS = {Duration.ofMillis(250), Duration.ofSeconds(1)};
    /** Stays under the cloud's 16 KiB per-item limit so transport framing never tips it over. */
    private static final int SAFE_PAYLOAD_BYTES = 15_360;

    private final CloudSyncApi api;
    private final CloudSessionService sessions;
    private final LearningEventQueryService query;
    private final LearningEventRecorder recorder;
    private final CloudSyncPreferences preferences;
    private final Path stateDirectory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private volatile SyncStatus currentStatus = SyncStatus.idle();

    public DefaultCloudLearningSyncService(CloudSyncApi api, CloudSessionService sessions,
            LearningEventQueryService query, LearningEventRecorder recorder, Path stateDirectory) {
        this(api, sessions, query, recorder,
            new CloudSyncPreferences() {
                @Override public boolean uploadPaused() { return false; }
                @Override public void uploadPaused(boolean paused) { }
                @Override public boolean autoSyncEnabled() { return false; }
                @Override public void autoSyncEnabled(boolean enabled) { }
            },
            stateDirectory);
    }

    public DefaultCloudLearningSyncService(CloudSyncApi api, CloudSessionService sessions,
            LearningEventQueryService query, LearningEventRecorder recorder,
            CloudSyncPreferences preferences, Path stateDirectory) {
        this.api = Objects.requireNonNull(api);
        this.sessions = Objects.requireNonNull(sessions);
        this.query = Objects.requireNonNull(query);
        this.recorder = Objects.requireNonNull(recorder);
        this.preferences = Objects.requireNonNull(preferences);
        this.stateDirectory = Objects.requireNonNull(stateDirectory);
    }

    @Override
    public synchronized SyncResult synchronize() {
        if (preferences.uploadPaused()) {
            // TFB-C1：用户暂停后任何触发路径都不上传，本地记录与作业离线队列不受影响。
            throw new IllegalStateException("学习记录同步已暂停，可在「班级与云端」页恢复");
        }
        var session = sessions.refresh().or(() -> sessions.current())
            .orElseThrow(() -> new IllegalStateException("请先登录云端账号"));
        String userId = session.user().id();
        PendingBatch batch = collectPending(userId);
        List<CloudSyncItem> pending = batch.items();
        currentStatus = new SyncStatus(SyncStatus.State.SYNCING, pending.size(), 1, lastSuccess(userId), null, null);
        persistStatus(userId, currentStatus);

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                SyncResult result = synchronizeOnce(session.accessToken(), userId, pending);
                // 全部上传成功后才推进水位，之后这些事件不再被重复收集上传。
                if (batch.maxEventId() > 0) {
                    write("cloud-sync-uploaded-cursor-" + safeKey(userId) + "-" + deviceId(), Long.toString(batch.maxEventId()));
                }
                currentStatus = new SyncStatus(SyncStatus.State.SUCCEEDED, 0, attempt, Instant.now(), null, null);
                persistStatus(userId, currentStatus);
                return result;
            } catch (RuntimeException error) {
                lastFailure = error;
                if (attempt == MAX_ATTEMPTS) break;
                Instant nextRetry = Instant.now().plus(RETRY_DELAYS[attempt - 1]);
                currentStatus = new SyncStatus(SyncStatus.State.RETRY_WAIT, pending.size(), attempt,
                    lastSuccess(userId), nextRetry, classify(error));
                persistStatus(userId, currentStatus);
                waitFor(RETRY_DELAYS[attempt - 1]);
                currentStatus = new SyncStatus(SyncStatus.State.SYNCING, pending.size(), attempt + 1,
                    lastSuccess(userId), null, null);
            }
        }

        currentStatus = new SyncStatus(SyncStatus.State.FAILED, pending.size(), MAX_ATTEMPTS,
            lastSuccess(userId), Instant.now().plus(Duration.ofMinutes(5)), classify(lastFailure));
        persistStatus(userId, currentStatus);
        throw new IllegalStateException("学习记录同步失败，已保留本地数据，可稍后重试", lastFailure);
    }

    @Override
    public SyncStatus status() {
        return currentStatus;
    }

    private SyncResult synchronizeOnce(String accessToken, String userId, List<CloudSyncItem> pending) {
        int uploaded = 0;
        for (int start = 0; start < pending.size(); start += 500) {
            uploaded += api.uploadSyncItems(accessToken, pending.subList(start, Math.min(start + 500, pending.size())));
        }
        String cursorFile = "cloud-sync-cursor-" + safeKey(userId) + ".txt";
        long cursor = Long.parseLong(readOrCreate(cursorFile, "0"));
        int downloaded = 0;
        String deviceId = readOrCreate("cloud-device-id.txt", UUID.randomUUID().toString());
        while (true) {
            List<CloudSyncItem> items = api.downloadSyncItems(accessToken, cursor);
            if (items.isEmpty()) break;
            for (CloudSyncItem item : items) {
                cursor = Math.max(cursor, item.version());
                if (item.id().startsWith(deviceId + ":")) continue;
                importItem(item, userId);
                downloaded++;
            }
            if (items.size() < 500) break;
        }
        write(cursorFile, Long.toString(cursor));
        return new SyncResult(uploaded, downloaded, cursor);
    }

    /** 一次查询收集全部待上传事件；已上传水位之后的事件才会被再次上传。 */
    private PendingBatch collectPending(String userId) {
        String deviceId = deviceId();
        long uploadedCursor = uploadedCursor(userId);
        List<CloudSyncItem> pending = new ArrayList<>();
        long maxEventId = 0;
        for (var event : query.queryAllEvents()) {
            if (event.id() <= uploadedCursor) continue;
            if (event.attributes().containsKey(CLOUD_ID)) continue;
            if (!userId.equals(event.attributes().get(LearningEventOwnerProvider.OWNER_ATTRIBUTE))) continue;
            try {
                String payload = pendingPayload(event);
                pending.add(new CloudSyncItem(deviceId + ":" + event.id(), event.type().name(), payload,
                    event.occurredAt(), 0));
                maxEventId = Math.max(maxEventId, event.id());
            } catch (IOException error) {
                throw new IllegalStateException("无法编码待同步学习记录", error);
            }
        }
        return new PendingBatch(List.copyOf(pending), maxEventId);
    }

    /**
     * TFB-D1: uploads pass the attribute allowlist so local bookkeeping keys never leave the
     * device. If long text evidence still pushes a payload near the cloud per-item limit, the
     * event is downgraded to metadata rather than dropped.
     */
    private String pendingPayload(LearningEventQueryService.QueriedLearningEvent event) throws IOException {
        String payload = payloadWith(event,
            LearningEventUploadPolicy.uploadAttributes(event.type(), event.attributes()));
        if (payload.getBytes(StandardCharsets.UTF_8).length <= SAFE_PAYLOAD_BYTES) return payload;
        return payloadWith(event,
            LearningEventUploadPolicy.uploadAttributesWithoutLongText(event.type(), event.attributes()));
    }

    private String payloadWith(
        LearningEventQueryService.QueriedLearningEvent event, Map<String, String> attributes
    ) throws IOException {
        return json.writeValueAsString(Map.of(
            "connectionId", event.connectionId(),
            "successful", event.successful(),
            "attributes", attributes
        ));
    }

    private record PendingBatch(List<CloudSyncItem> items, long maxEventId) { }

    private String deviceId() {
        return readOrCreate("cloud-device-id.txt", UUID.randomUUID().toString());
    }

    private long uploadedCursor(String userId) {
        try {
            return Long.parseLong(readOrCreate(
                "cloud-sync-uploaded-cursor-" + safeKey(userId) + "-" + deviceId(), "0"));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private void importItem(CloudSyncItem item, String userId) {
        try {
            Map<String, Object> payload = json.readValue(item.payloadJson(), new TypeReference<>() { });
            Map<String, String> attributes = new LinkedHashMap<>();
            Object raw = payload.get("attributes");
            if (raw instanceof Map<?, ?> map) {
                map.forEach((key, value) -> attributes.put(String.valueOf(key), String.valueOf(value)));
            }
            attributes.put(CLOUD_ID, item.id());
            attributes.put(LearningEventOwnerProvider.OWNER_ATTRIBUTE, userId);
            recorder.record(new LearningEvent(
                LearningEventType.valueOf(item.type()), item.occurredAt(),
                String.valueOf(payload.getOrDefault("connectionId", "cloud")),
                Boolean.parseBoolean(String.valueOf(payload.getOrDefault("successful", false))), attributes
            ));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("云端学习记录格式无效", error);
        }
    }

    private Instant lastSuccess(String userId) {
        Path file = statusFile(userId);
        if (Files.notExists(file)) return null;
        try {
            SyncStatus saved = json.readValue(file.toFile(), SyncStatus.class);
            return saved.lastSuccessAt();
        } catch (IOException ignored) {
            return null;
        }
    }

    private void persistStatus(String userId, SyncStatus status) {
        try {
            Files.createDirectories(stateDirectory);
            json.writeValue(statusFile(userId).toFile(), status);
        } catch (IOException error) {
            throw new IllegalStateException("无法保存同步状态", error);
        }
    }

    private Path statusFile(String userId) {
        return stateDirectory.resolve("cloud-sync-status-" + safeKey(userId) + ".json");
    }

    private String readOrCreate(String name, String fallback) {
        try {
            Files.createDirectories(stateDirectory);
            Path file = stateDirectory.resolve(name);
            if (Files.notExists(file)) Files.writeString(file, fallback);
            return Files.readString(file).trim();
        } catch (IOException error) {
            throw new IllegalStateException("无法读写同步状态", error);
        }
    }

    private void write(String name, String value) {
        try {
            Files.createDirectories(stateDirectory);
            Path target = stateDirectory.resolve(name);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, value);
            // Crash-safe replace: a truncated half-written state file must never survive.
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法保存同步状态", error);
        }
    }

    private static String safeKey(String userId) {
        return userId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String classify(RuntimeException error) {
        if (error == null) return "SYNC_UNKNOWN";
        if (error instanceof CloudApiRequestException request
                && (request.statusCode() == 401 || request.statusCode() == 403)) {
            return "SYNC_AUTH";
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("invalid")) return "SYNC_DATA";
        return "SYNC_NETWORK";
    }

    private static void waitFor(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同步重试已中断", error);
        }
    }
}
