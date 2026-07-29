package com.sqlteacher.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSqlSafetyModeServiceTest {
    @Test
    void shouldDefaultToStandardModeAndPersistChanges(@TempDir Path tempDirectory) {
        Path settings = tempDirectory.resolve("nested/sql-safety.properties");
        FileSqlSafetyModeService service = new FileSqlSafetyModeService(settings);

        assertFalse(service.isUnrestrictedModeEnabled());

        service.setUnrestrictedModeEnabled(true);
        assertTrue(new FileSqlSafetyModeService(settings).isUnrestrictedModeEnabled());

        service.setUnrestrictedModeEnabled(false);
        assertFalse(new FileSqlSafetyModeService(settings).isUnrestrictedModeEnabled());
    }
}
