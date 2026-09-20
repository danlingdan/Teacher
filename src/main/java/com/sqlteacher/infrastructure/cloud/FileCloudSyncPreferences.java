package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudSyncPreferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * File-backed sync preferences stored beside the other cloud sync state. Missing files mean
 * the defaults: uploads not paused, automatic sync enabled (v3.7.0 TFB-C1/C2 product
 * decision, 2026-09-20).
 */
public final class FileCloudSyncPreferences implements CloudSyncPreferences {
    private final Path stateDirectory;
    private final Object lock = new Object();

    public FileCloudSyncPreferences(Path stateDirectory) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory must not be null");
    }

    @Override
    public boolean uploadPaused() {
        return readFlag("cloud-sync-upload-paused.txt", false);
    }

    @Override
    public void uploadPaused(boolean paused) {
        writeFlag("cloud-sync-upload-paused.txt", paused);
    }

    @Override
    public boolean autoSyncEnabled() {
        return readFlag("cloud-sync-auto-enabled.txt", true);
    }

    @Override
    public void autoSyncEnabled(boolean enabled) {
        writeFlag("cloud-sync-auto-enabled.txt", enabled);
    }

    private boolean readFlag(String fileName, boolean fallback) {
        synchronized (lock) {
            try {
                Path file = stateDirectory.resolve(fileName);
                if (Files.notExists(file)) return fallback;
                return Boolean.parseBoolean(Files.readString(file).trim());
            } catch (IOException error) {
                // 读取失败按默认值处理：同步偏好不允许反过来阻断本地学习。
                return fallback;
            }
        }
    }

    private void writeFlag(String fileName, boolean value) {
        synchronized (lock) {
            try {
                Files.createDirectories(stateDirectory);
                Files.writeString(stateDirectory.resolve(fileName), Boolean.toString(value));
            } catch (IOException error) {
                throw new IllegalStateException("无法保存同步偏好", error);
            }
        }
    }
}
