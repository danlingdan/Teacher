package com.sqlteacher.application.update;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SemanticVersionTest {
    @Test void followsSemanticVersionPrecedenceIncludingLargeNumericIdentifiers() {
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.9")) > 0);
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.10.0-rc.1")) > 0);
        assertTrue(SemanticVersion.parse("1.0.0-rc.999999999999999999999").compareTo(SemanticVersion.parse("1.0.0-rc.2")) > 0);
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.10"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("01.10.0"));
    }

    @Test void rejectsUntrustedUpdateLocationsAndInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> manifest(URI.create("http://github.com/release.exe"), 10));
        assertThrows(IllegalArgumentException.class, () -> manifest(URI.create("https://example.com/release.exe"), 10));
        assertThrows(IllegalArgumentException.class, () -> manifest(URI.create("https://github.com/release.exe"), 0));
        assertDoesNotThrow(() -> manifest(URI.create("https://github.com/release.exe"), 10));
    }

    private static UpdateManifest manifest(URI installer, long size) {
        String hash = "a".repeat(64);
        return new UpdateManifest(1, "SQLTeacher", "stable", SemanticVersion.parse("1.10.0"), "windows", "x64",
            Instant.parse("2026-08-02T00:00:00Z"), URI.create("https://github.com/danlingdan/Teacher/releases/tag/v1.10.0"),
            installer, size, hash, URI.create("https://github.com/portable.zip"), 10, hash, SemanticVersion.parse("1.9.0"), null);
    }
}
