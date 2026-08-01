package com.sqlteacher.application.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstallIntegrityCheckerTest {
    @TempDir Path directory;

    @Test void reportsMissingAndTamperedFiles() throws Exception {
        Files.createDirectories(directory.resolve("bin"));
        Files.writeString(directory.resolve("bin/app.exe"), "real-bytes");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("bin/app.exe", InstallIntegrityChecker.sha256(directory.resolve("bin/app.exe")));
        expected.put("bin/missing.dll", "a".repeat(64));
        expected.put("app/app.jar", "b".repeat(64));

        var issues = InstallIntegrityChecker.check(directory, expected);
        assertEquals(2, issues.size());
        assertTrue(issues.stream().anyMatch(issue -> issue.kind().equals(InstallIntegrityChecker.Issue.MISSING) && issue.file().toString().contains("missing.dll")));
        assertTrue(issues.stream().anyMatch(issue -> issue.kind().equals(InstallIntegrityChecker.Issue.MISSING) && issue.file().toString().contains("app.jar")));
    }

    @Test void detectsHashMismatchWhenFileIsTampered() throws Exception {
        Files.createDirectories(directory.resolve("bin"));
        Files.writeString(directory.resolve("bin/app.exe"), "original");
        Map<String, String> expected = Map.of("bin/app.exe", InstallIntegrityChecker.sha256(directory.resolve("bin/app.exe")));
        assertEquals(0, InstallIntegrityChecker.check(directory, expected).size());

        Files.writeString(directory.resolve("bin/app.exe"), "tampered");
        var issues = InstallIntegrityChecker.check(directory, expected);
        assertEquals(1, issues.size());
        assertEquals(InstallIntegrityChecker.Issue.HASH_MISMATCH, issues.getFirst().kind());
    }

    @Test void missingBaseDirectoryIsReported() {
        var issues = InstallIntegrityChecker.check(directory.resolve("does-not-exist"), Map.of("bin/app.exe", "a".repeat(64)));
        assertEquals(1, issues.size());
        assertEquals(InstallIntegrityChecker.Issue.MISSING, issues.getFirst().kind());
    }
}
