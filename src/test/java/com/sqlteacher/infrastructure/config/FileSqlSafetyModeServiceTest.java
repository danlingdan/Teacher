package com.sqlteacher.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSqlSafetyModeServiceTest {
    @Test
    void shouldDefaultToDeveloperModeAndPersistChanges(@TempDir Path tempDirectory) {
        Path settings = tempDirectory.resolve("nested/sql-safety.properties");
        FileSqlSafetyModeService service = new FileSqlSafetyModeService(settings);

        assertTrue(service.isDeveloperModeEnabled());

        service.setUnrestrictedModeEnabled(true);
        assertTrue(new FileSqlSafetyModeService(settings).isUnrestrictedModeEnabled());

        service.setUnrestrictedModeEnabled(false);
        assertFalse(new FileSqlSafetyModeService(settings).isUnrestrictedModeEnabled());
    }

    @Test
    void shouldReadLegacyUnrestrictedModeChoice(@TempDir Path tempDirectory) throws Exception {
        Path settings = tempDirectory.resolve("sql-safety.properties");
        java.nio.file.Files.writeString(settings, "unrestricted-mode=false\n");
        assertFalse(new FileSqlSafetyModeService(settings).isDeveloperModeEnabled());
    }
}
