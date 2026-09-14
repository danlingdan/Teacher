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
    void shouldTreatLegacyUnrestrictedModeKeyAsNotChosen(@TempDir Path tempDirectory) throws Exception {
        // v3.4.0 removed the pre-3.2 "unrestricted-mode" key; holders return to the
        // not-chosen state so the first-run choice dialog appears again (fail closed).
        Path settings = tempDirectory.resolve("sql-safety.properties");
        java.nio.file.Files.writeString(settings, "unrestricted-mode=false\n");
        FileSqlSafetyModeService service = new FileSqlSafetyModeService(settings);
        assertFalse(service.isDeveloperModeEnabled());
        assertFalse(service.isDeveloperModeExplicit());
    }

    @Test
    void shouldFailClosedToTeachingModeWhenSettingsFileIsCorrupt(@TempDir Path tempDirectory) throws Exception {
        Path settings = tempDirectory.resolve("sql-safety.properties");
        java.nio.file.Files.write(settings, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01});

        FileSqlSafetyModeService service = new FileSqlSafetyModeService(settings);

        assertFalse(service.isDeveloperModeEnabled());
    }
}
