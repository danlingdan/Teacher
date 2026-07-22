package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.UserRole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Windows-only session store encrypted with DPAPI for the current OS user.
 * Other operating systems deliberately retain no session on disk.
 */
public final class WindowsDpapiCloudSessionStore implements CloudSessionStore {
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

    public WindowsDpapiCloudSessionStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public Optional<CloudAuthenticationService.Session> load() {
        if (!supported() || Files.notExists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(unprotect(Files.readString(file, StandardCharsets.US_ASCII).trim())));
        } catch (Exception ignored) {
            clear();
            return Optional.empty();
        }
    }

    @Override
    public void save(CloudAuthenticationService.Session session) {
        if (!supported()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "cloud-session-", ".tmp");
            try {
                Files.writeString(temporary, protect(encode(session)), StandardCharsets.US_ASCII);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to persist the cloud session in Windows secure storage", error);
        }
    }

    @Override
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Session removal is retried when the application next starts.
        }
    }

    private static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
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

    private static byte[] encode(CloudAuthenticationService.Session session) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(session.accessToken());
            output.writeBoolean(session.refreshToken() != null);
            if (session.refreshToken() != null) {
                output.writeUTF(session.refreshToken());
            }
            output.writeLong(session.expiresAt().toEpochMilli());
            output.writeUTF(session.user().id());
            output.writeUTF(session.user().email());
            output.writeUTF(session.user().displayName());
            output.writeInt(session.user().roles().size());
            for (UserRole role : session.user().roles()) {
                output.writeUTF(role.name());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to encode cloud session", error);
        }
    }

    private static CloudAuthenticationService.Session decode(byte[] encrypted) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encrypted))) {
            String accessToken = input.readUTF();
            String refreshToken = input.readBoolean() ? input.readUTF() : null;
            Instant expiresAt = Instant.ofEpochMilli(input.readLong());
            String id = input.readUTF();
            String email = input.readUTF();
            String displayName = input.readUTF();
            int count = input.readInt();
            if (count < 1 || count > UserRole.values().length) {
                throw new IOException("Invalid cloud session role count");
            }
            Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
            for (int index = 0; index < count; index++) {
                roles.add(UserRole.valueOf(input.readUTF()));
            }
            if (input.available() != 0) {
                throw new IOException("Unexpected cloud session data");
            }
            return new CloudAuthenticationService.Session(accessToken, expiresAt,
                new AuthenticatedUser(id, email, displayName, roles), refreshToken);
        }
    }
}
