package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.infrastructure.security.WindowsDpapiSecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class PersistentNetworkAiSettingsServiceTest {
    @Test
    void shouldPersistMetadataSeparatelyFromDpapiCredential(@TempDir Path tempDirectory) throws Exception {
        Path profileFile = tempDirectory.resolve("ai-providers.json");
        Path secretFile = tempDirectory.resolve("ai-provider-key.dat");
        char[] provided = "sensitive-test-key".toCharArray();
        var service = service(profileFile, secretFile);

        service.configure(URI.create("https://ai.example.test/v1"), "model-a", provided);

        assertTrue(allZero(provided));
        assertTrue(Files.isRegularFile(profileFile));
        assertTrue(Files.isRegularFile(secretFile));
        assertFalse(Files.readString(profileFile).contains("sensitive-test-key"));
        assertFalse(Files.readString(secretFile, StandardCharsets.US_ASCII).contains("sensitive-test-key"));
        service.close();

        var restored = service(profileFile, secretFile);
        var configuration = restored.current().orElseThrow();
        try {
            assertEquals(URI.create("https://ai.example.test/v1"), configuration.endpoint());
            assertEquals("model-a", configuration.model());
            assertEquals("sensitive-test-key", new String(configuration.apiKey()));
        } finally {
            configuration.destroy();
        }

        restored.clear();
        assertFalse(Files.exists(profileFile));
        assertFalse(Files.exists(secretFile));
    }

    @Test
    void shouldDisableAndCleanCorruptedProfile(@TempDir Path tempDirectory) throws Exception {
        Path profileFile = tempDirectory.resolve("ai-providers.json");
        Path secretFile = tempDirectory.resolve("ai-provider-key.dat");
        var service = service(profileFile, secretFile);
        service.configure(
            URI.create("https://ai.example.test/v1"), "model-a", "temporary-key".toCharArray()
        );
        service.close();
        Files.writeString(profileFile, "{broken-json");

        var corrupted = service(profileFile, secretFile);

        assertTrue(corrupted.current().isEmpty());
        assertFalse(Files.exists(profileFile));
        assertFalse(Files.exists(secretFile));
    }

    @Test
    void shouldKeepNetworkProviderDeactivatedAfterRestart(@TempDir Path tempDirectory) {
        Path profileFile = tempDirectory.resolve("ai-providers.json");
        Path secretFile = tempDirectory.resolve("ai-provider-key.dat");
        var service = service(profileFile, secretFile);
        service.configure(
            URI.create("https://ai.example.test/v1"), "model-a", "temporary-key".toCharArray()
        );
        service.deactivate();
        service.close();

        var restored = service(profileFile, secretFile);

        assertTrue(restored.activeProfile().isEmpty());
        assertTrue(restored.current().isEmpty());
        assertEquals(1, restored.profiles().size());
        restored.clear();
    }

    private static PersistentNetworkAiSettingsService service(Path profileFile, Path secretFile) {
        return new PersistentNetworkAiSettingsService(
            profileFile, new WindowsDpapiSecretStore(secretFile)
        );
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }
}
