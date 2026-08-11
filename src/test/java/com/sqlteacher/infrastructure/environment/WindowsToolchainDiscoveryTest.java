package com.sqlteacher.infrastructure.environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsToolchainDiscoveryTest {
    @TempDir Path tempDir;

    @Test
    void shouldFindTheJdkThatRunsTheBuildEvenWhenItIsNotTemurin() {
        Path compiler = WindowsToolchainDiscovery.javaCompiler();
        Path runtime = WindowsToolchainDiscovery.javaRuntime();

        assertNotNull(compiler);
        assertNotNull(runtime);
        assertTrue(Files.isRegularFile(compiler));
        assertTrue(Files.isRegularFile(runtime));
    }

    @Test
    void shouldSkipAnUnusableAliasAndContinueToTheNextCandidate() throws Exception {
        Path alias = Files.createFile(tempDir.resolve("python-alias.exe"));
        Path interpreter = Files.createFile(tempDir.resolve("python.exe"));

        Path selected = WindowsToolchainDiscovery.firstUsable(List.of(alias, interpreter),
            candidate -> candidate.equals(interpreter));

        assertEquals(interpreter, selected);
    }
}
