package com.sqlteacher.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSqlSafetyModeServiceTest {
    @Test
    void shouldDefaultToTeachingModeAndPersistChanges(@TempDir Path tempDirectory) {
        Path settings = tempDirectory.resolve("nested/sql-safety.properties");
        FileSqlSafetyModeService service = new FileSqlSafetyModeService(settings);

        assertFalse(service.isDeveloperModeEnabled());
        assertFalse(service.isDeveloperModeExplicit());

        service.setUnrestrictedModeEnabled(true);
        assertTrue(new FileSqlSafetyModeService(settings).isUnrestrictedModeEnabled());
        assertTrue(new FileSqlSafetyModeService(settings).isDeveloperModeExplicit());

        service.setUnrestrictedModeEnabled(false);
        assertFalse(new FileSqlSafetyModeService(settings).isUnrestrictedModeEnabled());
        assertTrue(new FileSqlSafetyModeService(settings).isDeveloperModeExplicit());
    }

    @Test
    void shouldReadLegacyUnrestrictedModeChoice(@TempDir Path tempDirectory) throws Exception {
        Path settings = tempDirectory.resolve("sql-safety.properties");
        java.nio.file.Files.writeString(settings, "unrestricted-mode=false\n");
        assertFalse(new FileSqlSafetyModeService(settings).isDeveloperModeEnabled());
        assertTrue(new FileSqlSafetyModeService(settings).isDeveloperModeExplicit());
    }

    @Test
    void shouldFailClosedToTeachingModeWhenSettingsFileIsCorrupt(@TempDir Path tempDirectory) throws Exception {
        Path settings = tempDirectory.resolve("sql-safety.properties");
        java.nio.file.Files.write(settings, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01});

        FileSqlSafetyModeService service = new FileSqlSafetyModeService(settings);

        assertFalse(service.isDeveloperModeEnabled());
    }
}
