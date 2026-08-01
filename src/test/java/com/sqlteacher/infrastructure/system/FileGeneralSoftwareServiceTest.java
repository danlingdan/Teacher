package com.sqlteacher.infrastructure.system;

import com.sqlteacher.application.system.AppNotification;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sqlteacher.application.system.TaskSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileGeneralSoftwareServiceTest {
    @TempDir Path directory;

    @Test void persistsWhitelistedSettingsAndQuarantinesCorruption() throws Exception {
        FileGeneralSoftwareService service = new FileGeneralSoftwareService(directory, URI.create("https://api.sqlteacher.tech"));
        GeneralSoftwareSettings changed = new GeneralSoftwareSettings(1, false, "1.10.1", GeneralSoftwareSettings.ProxyMode.MANUAL,
            "127.0.0.1", 8080, true, true, false, 0, true, "en");
        service.saveSettings(changed);
        assertEquals(changed, service.settings());
        Path file = directory.resolve("support/general-settings.json");
        Files.writeString(file, "{not-json");
        assertEquals(GeneralSoftwareSettings.defaults(), service.settings());
        assertTrue(Files.isRegularFile(file.resolveSibling("general-settings.json.corrupt")));
    }

    @Test void boundsAndDeduplicatesOperationalStateAndOnlyClearsRebuildableData() throws Exception {
        FileGeneralSoftwareService service = new FileGeneralSoftwareService(directory, URI.create("https://api.sqlteacher.tech"));
        String task = service.startTask("UPDATE", "download", true); service.updateTask(task, 2); service.completeTask(task);
        assertEquals(TaskSnapshot.Status.SUCCEEDED, service.tasks().getFirst().status());
        service.notify(AppNotification.Category.UPDATE, "update", "one", "updates");
        service.notify(AppNotification.Category.UPDATE, "update", "two", "updates");
        assertEquals(1, service.notifications().size());
        Files.createDirectories(directory.resolve("cache")); Files.writeString(directory.resolve("cache/item"), "cache");
        Files.writeString(directory.resolve("student.db"), "keep");
        assertTrue(service.clearRebuildableFiles() > 0);
        assertTrue(Files.isRegularFile(directory.resolve("student.db")));
        assertFalse(Files.exists(directory.resolve("cache/item")));
    }
}
