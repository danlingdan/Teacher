package com.sqlteacher.infrastructure.support;

import com.sqlteacher.application.support.DiagnosticSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FileDiagnosticBundleServiceTest {
    @TempDir Path directory;

    @Test void distinguishesFirstStartCrashAndCleanShutdown() {
        FileDiagnosticBundleService first = new FileDiagnosticBundleService(directory);
        assertFalse(first.previousRunCrashed());
        FileDiagnosticBundleService afterCrash = new FileDiagnosticBundleService(directory);
        assertTrue(afterCrash.previousRunCrashed());
        afterCrash.markCleanShutdown();
        assertFalse(new FileDiagnosticBundleService(directory).previousRunCrashed());
    }

    @Test void exportsOnlyWhitelistedDiagnosticEntriesAndRedactsSecrets() throws Exception {
        FileDiagnosticBundleService service = new FileDiagnosticBundleService(directory);
        service.recordFailure(new IllegalStateException("Bearer secret-value at C:\\Users\\student\\file"), "background token=secret-value");
        Path archive = service.export(new DiagnosticSelection(true, true, true, true), directory.resolve("exports"));
        assertTrue(Files.isRegularFile(archive));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("README.txt"));
            assertNull(zip.getEntry("database.db"));
            String errors = new String(zip.getInputStream(zip.getEntry("recentErrors.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(errors.contains("secret-value"));
        }
    }
}
