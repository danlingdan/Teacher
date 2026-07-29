package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.infrastructure.security.WindowsDpapiSecretStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Windows-only session store encrypted with DPAPI for the current OS user.
 * Other operating systems deliberately retain no session on disk.
 */
public final class WindowsDpapiCloudSessionStore implements CloudSessionStore {
    private final WindowsDpapiSecretStore secretStore;

    public WindowsDpapiCloudSessionStore(Path file) {
        this.secretStore = new WindowsDpapiSecretStore(file);
    }

    @Override
    public Optional<CloudAuthenticationService.Session> load() {
        try {
            Optional<byte[]> stored = secretStore.load();
            if (stored.isEmpty()) return Optional.empty();
            byte[] bytes = stored.get();
            try {
                return Optional.of(decode(bytes));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } catch (Exception ignored) {
            clear();
            return Optional.empty();
        }
    }

    @Override
    public void save(CloudAuthenticationService.Session session) {
        byte[] encoded = encode(session);
        try {
            secretStore.save(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    @Override
    public void clear() {
        secretStore.clear();
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
