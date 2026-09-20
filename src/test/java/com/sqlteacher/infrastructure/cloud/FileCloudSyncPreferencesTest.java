package com.sqlteacher.infrastructure.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.7.0 TFB-C1/C2: file-backed sync preferences default open and round-trip. */
class FileCloudSyncPreferencesTest {
    @TempDir Path stateDirectory;

    @Test
    void defaultsToUploadEnabledWithAutoSyncOn() {
        var preferences = new FileCloudSyncPreferences(stateDirectory);

        assertFalse(preferences.uploadPaused());
        assertTrue(preferences.autoSyncEnabled());
    }

    @Test
    void roundTripsBothFlagsAcrossInstances() {
        var first = new FileCloudSyncPreferences(stateDirectory);
        first.uploadPaused(true);
        first.autoSyncEnabled(false);

        var second = new FileCloudSyncPreferences(stateDirectory);
        assertTrue(second.uploadPaused());
        assertFalse(second.autoSyncEnabled());

        second.uploadPaused(false);
        second.autoSyncEnabled(true);
        assertFalse(second.uploadPaused());
        assertTrue(second.autoSyncEnabled());
    }

    @Test
    void unreadableStateFallsBackToDefaultsInsteadOfBlockingLearning() throws Exception {
        Path file = stateDirectory.resolve("cloud-sync-upload-paused.txt");
        java.nio.file.Files.createDirectories(stateDirectory);
        java.nio.file.Files.writeString(file, "not-a-boolean");

        var preferences = new FileCloudSyncPreferences(stateDirectory);

        // 解析失败按"未暂停"处理：坏状态文件不能反过来禁用同步；覆写后恢复正常。
        assertFalse(preferences.uploadPaused());
        preferences.uploadPaused(true);
        assertTrue(preferences.uploadPaused());
    }
}
