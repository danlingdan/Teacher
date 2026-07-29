package com.sqlteacher.infrastructure.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Windows DPAPI encrypted binary storage scoped to the current operating-system user. */
public final class WindowsDpapiSecretStore {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final String PROTECT = """
        Add-Type -AssemblyName System.Security
        $data = [Convert]::FromBase64String([Console]::In.ReadToEnd())
        $scope = [System.Security.Cryptography.DataProtectionScope]::CurrentUser
        [Console]::Out.Write([Convert]::ToBase64String([System.Security.Cryptography.ProtectedData]::Protect($data, $null, $scope)))
        """;
    private static final String UNPROTECT = """
        Add-Type -AssemblyName System.Security
        $data = [Convert]::FromBase64String([Console]::In.ReadToEnd())
        $scope = [System.Security.Cryptography.DataProtectionScope]::CurrentUser
        [Console]::Out.Write([Convert]::ToBase64String([System.Security.Cryptography.ProtectedData]::Unprotect($data, $null, $scope)))
        """;

    private final Path file;

    public WindowsDpapiSecretStore(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
    }

    public Optional<byte[]> load() {
        if (!supported() || Files.notExists(file)) return Optional.empty();
        try {
            String encrypted = Files.readString(file, StandardCharsets.US_ASCII).trim();
            if (encrypted.isBlank()) throw new IllegalStateException("Encrypted secret is empty");
            return Optional.of(unprotect(encrypted));
        } catch (RuntimeException | IOException error) {
            clear();
            return Optional.empty();
        }
    }

    public void save(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length == 0) throw new IllegalArgumentException("value must not be empty");
        if (!supported()) return;
        Path parent = file.getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(temporary, protect(value), StandardCharsets.US_ASCII);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to persist a secret in Windows secure storage", error);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A later save will replace the fixed temporary file.
            }
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Removal is retried by the next explicit clear or replacement save.
        }
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String protect(byte[] value) {
        return invoke(PROTECT, value);
    }

    private static byte[] unprotect(String value) {
        return Base64.getDecoder().decode(invoke(UNPROTECT, Base64.getDecoder().decode(value)));
    }

    private static String invoke(String command, byte[] input) {
        Process process;
        try {
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true)
                .start();
            process.getOutputStream().write(Base64.getEncoder().encode(input));
            process.getOutputStream().close();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Windows secure storage operation timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                throw new IllegalStateException("Windows secure storage operation failed");
            }
            return output;
        } catch (IOException error) {
            throw new IllegalStateException("Windows secure storage is unavailable", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Windows secure storage operation was interrupted", error);
        }
    }
}
