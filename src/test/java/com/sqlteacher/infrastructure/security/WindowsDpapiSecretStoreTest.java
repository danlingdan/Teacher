package com.sqlteacher.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiSecretStoreTest {
    @Test
    void shouldEncryptRoundTripAndClearSecret(@TempDir Path tempDirectory) throws Exception {
        Path file = tempDirectory.resolve("secret.dat");
        WindowsDpapiSecretStore store = new WindowsDpapiSecretStore(file);
        byte[] secret = "test-api-key-value".getBytes(StandardCharsets.UTF_8);

        store.save(secret);

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.readString(file, StandardCharsets.US_ASCII).contains("test-api-key-value"));
        assertArrayEquals(secret, store.load().orElseThrow());

        store.clear();
        assertFalse(Files.exists(file));
    }

    @Test
    void shouldDiscardCorruptedCiphertext(@TempDir Path tempDirectory) throws Exception {
        Path file = tempDirectory.resolve("secret.dat");
        Files.writeString(file, "not-dpapi-data", StandardCharsets.US_ASCII);
        WindowsDpapiSecretStore store = new WindowsDpapiSecretStore(file);

        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(file));
    }
}
